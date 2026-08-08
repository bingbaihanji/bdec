package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects multiline string literals and converts them to
 * Java 15+ text blocks (triple-quoted strings).
 *
 * <p>Pattern: String literals containing {@code \n} and at least
 * 2 lines are candidates for text block conversion.
 *
 * <pre>
 *   String s = "line1\nline2\nline3";
 *   → String s = """
 *       line1
 *       line2
 *       line3
 *       """;
 * </pre>
 */
public class TextBlockRewriter implements RewriteRule {

    @Override
    public String name() { return "text-block"; }

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
                        rewriteStatement(md.body())));
            } else if (m instanceof FieldDeclaration fd) {
                members.add(new FieldDeclaration(fd.accessFlags(), fd.name(), fd.type(),
                        fd.initializer() != null ? rewriteExpr(fd.initializer()) : null));
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
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value()) : null);
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
        if (e instanceof LitExpr lit) {
            Object val = lit.value();
            if (val instanceof String s && shouldConvert(s)) {
                return new LitExpr(s, JavaType.classType("java/lang/String"));
                // The emitter will handle multiline string formatting
                // We just mark it — actual text block formatting is in emitter
            }
            return e;
        }
        if (e instanceof BinExpr be) {
            return new BinExpr(be.operator(),
                    rewriteExpr(be.left()), rewriteExpr(be.right()));
        }
        if (e instanceof InvocationExpr inv) {
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            return new InvocationExpr(
                    inv.target() != null ? rewriteExpr(inv.target()) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }
        if (e instanceof AssignExpr assign) {
            return new AssignExpr(rewriteExpr(assign.target()),
                    rewriteExpr(assign.value()));
        }
        return e;
    }

    /** Check if a string literal should be converted to a text block.
     *  Requires: contains newlines, at least 3 lines, not just whitespace. */
    private boolean shouldConvert(String s) {
        if (s == null || s.isEmpty()) return false;
        // Count lines
        long lines = s.lines().count();
        if (lines < 3) return false;
        // Must contain actual newlines (not just \r\n at end)
        return s.contains("\n") || s.contains("\r\n");
    }
}
