package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects {@code invokedynamic} patterns that resolve to method references
 * and converts them to Java {@code ::} syntax.
 *
 * <p>Four kinds of method references:
 * <pre>
 *   Static:        ClassName::staticMethod      (INVOKESTATIC)
 *   Bound:         expr::instanceMethod          (INVOKEVIRTUAL, receiver captured)
 *   Unbound:       ClassName::instanceMethod     (INVOKEVIRTUAL, receiver = 1st param)
 *   Constructor:   ClassName::new                (NEW + INVOKESPECIAL init)
 * </pre>
 *
 * <p>Inspired by CFR's lambda handling and Vineflower's {@code LambdaProcessor}.
 */
public class MethodRefRewriter implements RewriteRule {

    @Override
    public String name() { return "method-ref"; }

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<BootstrapMethodEntry> bootstrapMethods = context.bootstrapMethods();
        if (bootstrapMethods == null || bootstrapMethods.isEmpty()) {
            return unit;
        }

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        rewriteStatement(md.body())));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(this::rewriteStatement).toList());
        }
        if (s instanceof ExpressionStatement es) {
            Expression rewritten = rewriteExpr(es.expression());
            return new ExpressionStatement(rewritten);
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value()) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(rewriteExpr(i.condition()),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        return s;
    }

    private Expression rewriteExpr(Expression e) {
        if (e instanceof InvocationExpr inv) {
            // Recurse first
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            Expression newTarget = inv.target() != null
                    ? rewriteExpr(inv.target()) : null;

            // Detect method reference pattern
            String name = inv.methodName();
            if (name != null && newTarget == null && !newArgs.isEmpty()) {
                // Possible method reference: invokedynamic with static args
                Expression methodRef = tryConvertMethodRef(name, newArgs);
                if (methodRef != null) return methodRef;
            }

            return new InvocationExpr(newTarget, name, newArgs, inv.returnType());
        }
        return e;
    }

    /**
     * Try to convert an invokedynamic call to a {@code ::} method reference.
     * Pattern: invokedynamic name contains "$$" or starts with known lambda/sam prefixes.
     */
    private Expression tryConvertMethodRef(String name, List<Expression> args) {
        // Pattern 1: new ClassName() constructor reference
        if (name.startsWith("new") && args.size() == 1
                && args.get(0) instanceof VarExpr vx) {
            return new VarExpr(vx.name() + "::new");
        }

        // Pattern 2: static method reference Class::method
        if (name.contains("::")) {
            return new VarExpr(name); // already formatted
        }

        // Pattern 3: general method reference pattern from indy
        // methodName$hash or lambda$method$N → extract
        if (name.contains("$") && args.size() >= 1) {
            // Try to format as Class::method
            String clean = name.replace("lambda$", "").replace("$", "::");
            if (clean.contains("::")) {
                return new VarExpr(clean);
            }
        }

        return null;
    }
}
