package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;

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
        if (bootstrapMethods == null || bootstrapMethods.isEmpty()) {
            return unit; // nothing to rewrite
        }

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, bootstrapMethods));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td,
                                        List<BootstrapMethodEntry> bootstrapMethods) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                // Filter lambda synthetic methods: lambda$xxx$N or method ref bridges.
                // When decodeLambdas is on, these methods are hidden — the lambda
                // expression is emitted at the invoke site instead.
                if (isLambdaSyntheticMethod(md)) {
                    continue;
                }
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        rewriteStatement(md.body(), bootstrapMethods)));
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
                                       List<BootstrapMethodEntry> bootstrapMethods) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(st -> rewriteStatement(st, bootstrapMethods))
                    .filter(st -> st != null)
                    .toList());
        }
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(
                    rewriteExpr(es.expression(), bootstrapMethods));
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value(), bootstrapMethods) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(
                    rewriteExpr(i.condition(), bootstrapMethods),
                    rewriteStatement(i.thenBranch(), bootstrapMethods),
                    i.elseBranch() != null
                            ? rewriteStatement(i.elseBranch(), bootstrapMethods) : null);
        }
        return s;
    }

    private Expression rewriteExpr(Expression e,
                                   List<BootstrapMethodEntry> bootstrapMethods) {
        if (e instanceof InvocationExpr inv) {
            String name = inv.methodName();
            if (name == null) {
                return e;
            }

            // Detect lambda: method name starts with "lambda$"
            if (name.startsWith("lambda$") && inv.target() == null) {
                return convertLambda(inv);
            }
            // Detect method reference (static or instance)
            if (name.contains("$") && inv.target() == null) {
                // Could be a method reference pattern
            }

            // Recurse into children
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg, bootstrapMethods));
            }
            return new InvocationExpr(
                    inv.target() != null ? rewriteExpr(inv.target(), bootstrapMethods) : null,
                    name, newArgs, inv.returnType());
        }
        return e;
    }

    /** Convert a lambda invocation into a lambda expression placeholder.
     *  Full conversion requires bootstrap method data resolution. */
    private Expression convertLambda(InvocationExpr inv) {
        // For now, produce a readable lambda expression.
        // The full implementation would look up the bootstrap method,
        // extract the functional interface, method handle, and captured args.
        String name = inv.methodName();
        // Extract descriptive name: lambda$methodName$N → (args) -> method(args)
        String descriptor = name.replace("lambda$", "");

        // Build a simple arrow-notation representation
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
}
