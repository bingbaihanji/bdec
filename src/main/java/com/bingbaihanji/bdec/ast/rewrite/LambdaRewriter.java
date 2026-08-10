package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.semantic.SemanticReconstructor;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects {@code invokedynamic} / {@code LambdaMetafactory} patterns and
 * converts them to Java lambda expressions.
 *
 * <p>Pattern (at IR/AST level):
 * <pre>
 *   invokeDynamic("lambda$method$0", ...)  →  (args) -> body
 *   invokeDynamic("methodRef", ...)        →  Class::method
 * </pre>
 *
 * <p>The bootstrap methods data from the class file is used to resolve
 * the functional interface type, target method handle, and captured args.
 *
 * <p>Inspired by CFR's {@code LambdaExpressionRewriter} and
 * Vineflower's {@code LambdaProcessor}.
 */
public class LambdaRewriter implements RewriteRule {

    /** ACC_SYNTHETIC flag (0x1000). */
    private static final int ACC_SYNTHETIC = 0x1000;

    @Override
    public String name() {return "lambda";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<BootstrapMethodEntry> bootstrapMethods = context.bootstrapMethods();
        ClassFileModel cfm = context.classFile();

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, bootstrapMethods, cfm, context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td,
                                        List<BootstrapMethodEntry> bootstrapMethods,
                                        ClassFileModel cfm,
                                        DecompileContext ctx) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                // Filter lambda synthetic methods: lambda$xxx$N or method ref bridges.
                if (isLambdaSyntheticMethod(md)) {
                    continue;
                }
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        md.body() != null
                                ? rewriteStatement(md.body(), bootstrapMethods, cfm, ctx)
                                : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /** Check if a method is a lambda synthetic (lambda$xxx$N or method ref bridge). */
    private boolean isLambdaSyntheticMethod(MethodDeclaration md) {
        String name = md.name();
        if (name == null) {
            return false;
        }
        // Lambda body methods: lambda$enclosingMethod$N
        if (name.startsWith("lambda$")) {
            return true;
        }
        // Method reference bridge: lambda$xxx$N pattern is most common;
        // other synthetic patterns are harder to detect without class context
        return false;
    }

    private Statement rewriteStatement(Statement s,
                                       List<BootstrapMethodEntry> bootstrapMethods,
                                       ClassFileModel cfm,
                                       DecompileContext ctx) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(st -> rewriteStatement(st, bootstrapMethods, cfm, ctx))
                    .filter(st -> st != null)
                    .toList());
        }
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(
                    rewriteExpr(es.expression(), bootstrapMethods, cfm, ctx));
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value(), bootstrapMethods, cfm, ctx) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(
                    rewriteExpr(i.condition(), bootstrapMethods, cfm, ctx),
                    rewriteStatement(i.thenBranch(), bootstrapMethods, cfm, ctx),
                    i.elseBranch() != null
                            ? rewriteStatement(i.elseBranch(), bootstrapMethods, cfm, ctx)
                            : null);
        }
        return s;
    }

    private Expression rewriteExpr(Expression e,
                                   List<BootstrapMethodEntry> bootstrapMethods,
                                   ClassFileModel cfm,
                                   DecompileContext ctx) {
        // Replace LambdaExpr placeholders with decompiled bodies.
        if (e instanceof LambdaExpr lambda && !lambda.isMethodRef()
                && lambda.bodyExpr() instanceof VarExpr ve
                && ve.name().startsWith("/* lambda")) {
            return buildLambdaBody(lambda, ve.name(), cfm, ctx);
        }

        if (e instanceof InvocationExpr inv) {
            String name = inv.methodName();
            if (name == null) {
                return e;
            }

            // Detect lambda: method name starts with "lambda$"
            if (name.startsWith("lambda$") && inv.target() == null) {
                return convertLambda(inv);
            }

            // Recurse into children
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg, bootstrapMethods, cfm, ctx));
            }
            return new InvocationExpr(
                    inv.target() != null
                            ? rewriteExpr(inv.target(), bootstrapMethods, cfm, ctx) : null,
                    name, newArgs, inv.returnType());
        }
        return e;
    }

    /** Convert a lambda invocation into a lambda expression placeholder. */
    private Expression convertLambda(InvocationExpr inv) {
        String name = inv.methodName();
        String descriptor = name.replace("lambda$", "");

        List<Expression> args = inv.arguments();
        StringBuilder lambdaText = new StringBuilder("(");
        if (!args.isEmpty()) {
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    lambdaText.append(", ");
                }
                lambdaText.append("arg").append(i);
            }
        }
        lambdaText.append(") -> /* ").append(descriptor).append(" */");

        return new VarExpr(lambdaText.toString());
    }

    /** Decompile the body of a lambda synthetic method and produce a real
     *  LambdaExpr instead of a placeholder. */
    private LambdaExpr buildLambdaBody(LambdaExpr placeholder, String bodyHint,
                                        ClassFileModel cfm, DecompileContext ctx) {
        // Extract method name: "/* lambda$methodName$N */" → "lambda$methodName$N"
        String methodName = bodyHint.replace("/* ", "").replace(" */", "").trim();
        if (methodName.isEmpty()) {
            return placeholder;
        }

        MethodModel lambdaMethod = findLambdaMethod(cfm, methodName);
        if (lambdaMethod == null) {
            return placeholder;
        }

        try {
            CfgBuilder cfgBuilder = new CfgBuilder();
            IrBuilder irBuilder = new IrBuilder();
            SemanticReconstructor sr = new SemanticReconstructor();
            ControlFlowStructurer structurer = new ControlFlowStructurer();

            ControlFlowGraph cfg = cfgBuilder.build(lambdaMethod);
            LinearIr ir = irBuilder.build(cfg, lambdaMethod,
                    cfm.constantPool(), cfm.bootstrapMethods());
            ir = sr.reconstruct(ir, lambdaMethod, cfg, cfm);
            StructuredMethod sm = structurer.structure(ir, ctx);

            if (sm.body() == null) {
                return placeholder;
            }

            // Try to extract an expression body from the decompiled method.
            // Lambda methods typically have the form: return expr;
            Expression bodyExpr = extractReturnExpr(sm.body());
            if (bodyExpr != null) {
                return LambdaExpr.expression(placeholder.parameters(),
                        bodyExpr, placeholder.functionalType());
            }

            // Block lambda: use the method body directly (minus the outer
            // return for the last statement if present).
            return LambdaExpr.block(placeholder.parameters(),
                    stripOuterReturn(sm.body()), placeholder.functionalType());
        } catch (Exception e) {
            return placeholder;
        }
    }

    /** Find the lambda synthetic method by name in the class file. */
    private static MethodModel findLambdaMethod(ClassFileModel cfm, String methodName) {
        for (MethodModel m : cfm.methods()) {
            if (methodName.equals(m.name())) {
                return m;
            }
        }
        return null;
    }

    /** If the method body is a single "return expr;", extract the expression.
     *  Returns null if the body has multiple statements or isn't a simple return. */
    private static Expression extractReturnExpr(BlockStatement body) {
        List<Statement> stmts = body.statements();
        if (stmts.size() == 1 && stmts.get(0) instanceof ReturnStatement rs
                && rs.value() != null) {
            return rs.value();
        }
        return null;
    }

    /** Strip the outer return from a block body, converting the last
     *  "return expr;" to just "expr;" for expression-style lambdas.
     *  For block lambdas, keep all statements but remove the final
     *  synthetic return that was added by wrapAsReturn. */
    private static BlockStatement stripOuterReturn(BlockStatement body) {
        List<Statement> stmts = new ArrayList<>(body.statements());
        // Remove trailing synthetic "return null/0/false" added by the
        // structurer for non-void methods.
        if (!stmts.isEmpty()) {
            Statement last = stmts.get(stmts.size() - 1);
            if (last instanceof ReturnStatement rs && rs.value() instanceof LitExpr lit
                    && (lit.value() == null || lit.value() instanceof Number n
                    && n.intValue() == 0 || Boolean.FALSE.equals(lit.value()))) {
                stmts.remove(stmts.size() - 1);
            }
        }
        return new BlockStatement(stmts);
    }
}
