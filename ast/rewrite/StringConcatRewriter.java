package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts StringBuilder append chains and {@code invokedynamic} string
 * concatenation patterns into Java {@code +} operator expressions.
 *
 * <p>Patterns:
 * <pre>
 *   new StringBuilder().append(a).append(b).toString()  →  a + b
 *   "prefix" + a + "suffix" (via makeConcatWithConstants) → "prefix" + a + "suffix"
 * </pre>
 *
 * <p>Inspired by CFR's {@code sugarstringbuilder} and Vineflower's
 * {@code ConcatenationHelper}.
 */
public class StringConcatRewriter implements RewriteRule {

    @Override
    public String name() {return "string-concat";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
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
                        md.body() != null ? rewriteStatement(md.body()) : null));
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
            return new ExpressionStatement(rewriteExpr(es.expression()));
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null ? rewriteExpr(rs.value()) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(rewriteExpr(i.condition()),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        if (s instanceof ThrowStatement ts) {
            return new ThrowStatement(rewriteExpr(ts.expression()));
        }
        return s;
    }

    private Expression rewriteExpr(Expression e) {
        // StringBuilder chain: new StringBuilder().append(a).append(b).toString()
        if (e instanceof InvocationExpr inv && "toString".equals(inv.methodName())
                && inv.arguments().isEmpty() && inv.target() != null) {
            Expression chain = unwindStringBuilder(inv.target());
            if (chain != null) {
                return chain;
            }
        }

        // InvokeDynamic concat: makeConcatWithConstants(arg1, arg2, ...)
        if (e instanceof InvocationExpr inv && "makeConcatWithConstants".equals(inv.methodName())) {
            return buildConcatExpr(inv.arguments());
        }

        // Recursively rewrite children of InvocationExpr
        if (e instanceof InvocationExpr inv) {
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            return new InvocationExpr(
                    inv.target() != null ? rewriteExpr(inv.target()) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }

        return e;
    }

    /** Unwind a chain of StringBuilder.append() calls into a list of expressions. */
    private Expression unwindStringBuilder(Expression e) {
        List<Expression> parts = new ArrayList<>();
        Expression current = e;
        while (current instanceof InvocationExpr inv
                && "append".equals(inv.methodName())
                && inv.arguments().size() == 1) {
            parts.addFirst(rewriteExpr(inv.arguments().getFirst()));
            current = inv.target();
        }
        // The root should be new StringBuilder()
        if (current instanceof InvocationExpr inv
                && "append".equals(inv.methodName()) && inv.target() instanceof NewExpr ne
                && ne.instantiatedType().internalName() != null
                && ne.instantiatedType().internalName().contains("StringBuilder")) {
            if (inv.arguments().size() == 1) {
                parts.addFirst(rewriteExpr(inv.arguments().getFirst()));
            }
        } else if (!(current instanceof NewExpr)) {
            return null; // not a StringBuilder pattern
        }
        return buildConcatExpr(parts);
    }

    /** Check if an expression already produces a String-compatible value. */
    private boolean looksLikeString(Expression e) {
        if (e instanceof LitExpr lit && lit.value() instanceof String) {
            return true;
        }
        if (e instanceof InvocationExpr inv && "toString".equals(inv.methodName())) {
            return true;
        }
        return false;
    }

    /** Build a chain of + from a list of expressions.
     *  Ensures the first operand is String-typed so the whole chain
     *  produces a String (Java's string concatenation promotion). */
    private Expression buildConcatExpr(List<Expression> parts) {
        if (parts.isEmpty()) {
            return new LitExpr("", JavaType.classType("java/lang/String"));
        }
        if (parts.size() == 1) {
            // Single part: ensure it's String-compatible
            Expression single = parts.get(0);
            if (looksLikeString(single)) {
                return single;
            }
            return new BinExpr(BinaryOperator.ADD,
                    new LitExpr("", JavaType.classType("java/lang/String")), single);
        }
        // Ensure first part is String-typed so Java's + produces String
        Expression first = parts.get(0);
        if (!looksLikeString(first)) {
            parts = new ArrayList<>(parts);
            parts.add(0, new LitExpr("", JavaType.classType("java/lang/String")));
        }
        Expression result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = new BinExpr(BinaryOperator.ADD, result, parts.get(i));
        }
        return result;
    }
}
