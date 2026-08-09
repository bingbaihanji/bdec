package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.*;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import java.util.*;

/**
 * Minimal safety-net rewriter. Only fixes patterns that cause compile errors:
 * (1) void method calls wrapped in return statements
 * (2) undeclared exception variables in throw statements
 * (3) duplicate variable declarations in the same block
 */
public class SourceCleanup implements RewriteRule {
    @Override public String name() {return "source-cleanup";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext ctx) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) types.add(cleanupType(td));
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration cleanupType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                boolean nonVoid = md.returnType() != null
                        && md.returnType().kind() != TypeKind.VOID;
                members.add(new MethodDeclaration(md.accessFlags(), md.name(),
                        md.returnType(), md.parameterNames(), md.parameterTypes(),
                        md.typeParameters(),
                        fix(md.body(), nonVoid, md.returnType())));
            } else members.add(m);
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    private Statement fix(Statement s, boolean nonVoid, JavaType retType) {
        if (s == null) return null;
        if (s instanceof ReturnStatement rs && rs.value() != null && nonVoid) {
            Expression v = rs.value();
            if (v instanceof InvocationExpr inv && isVoid(inv)) {
                return new BlockStatement(List.of(
                        new ExpressionStatement(v),
                        new ReturnStatement(defaultVal(retType))));
            }
            return s;
        }
        if (s instanceof ThrowStatement ts && ts.expression() instanceof VarExpr ve
                && ve.name().startsWith("var")) {
            return new BlockStatement(List.of(
                    new VariableDeclaration(JavaType.classType("java/lang/Throwable"),
                            ve.name(), null), s));
        }
        if (s instanceof BlockStatement bs) {
            List<Statement> cleaned = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // First pass: collect all declared names in this block
            Set<String> allDeclared = new HashSet<>();
            collectDeclared(bs, allDeclared);
            for (Statement c : bs.statements()) {
                if (c instanceof VariableDeclaration vd) {
                    if (!seen.add(vd.name()) && vd.initializer() != null) {
                        cleaned.add(new ExpressionStatement(
                                new AssignExpr(new VarExpr(vd.name()), vd.initializer())));
                        continue;
                    }
                }
                // Check for undeclared variable uses before this statement
                Set<String> used = new HashSet<>();
                collectVarNames(c, used);
                for (String u : used) {
                    if (!allDeclared.contains(u) && !seen.contains(u)
                            && !isBuiltin(u)) {
                        cleaned.add(new VariableDeclaration(
                                JavaType.INT, u, null));
                        seen.add(u);
                        allDeclared.add(u);
                    }
                }
                cleaned.add(fix(c, nonVoid, retType));
            }
            return new BlockStatement(cleaned);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    fix(i.thenBranch(), nonVoid, retType),
                    i.elseBranch() != null ? fix(i.elseBranch(), nonVoid, retType) : null);
        }
        if (s instanceof LoopStatement l) {
            return new LoopStatement(l.loopKind(), l.condition(),
                    fix(l.body(), nonVoid, retType));
        }
        if (s instanceof TryStatement t) {
            List<TryStatement.CatchClause> cc = new ArrayList<>();
            for (var c : t.catchClauses())
                cc.add(new TryStatement.CatchClause(c.exceptionType(), c.varName(),
                        fix(c.body(), nonVoid, retType)));
            return new TryStatement(fix(t.tryBody(), nonVoid, retType), cc,
                    t.finallyBody() != null ? fix(t.finallyBody(), nonVoid, retType) : null);
        }
        return s;
    }

    private boolean isVoid(InvocationExpr inv) {
        return inv.returnType() != null && inv.returnType().kind() == TypeKind.VOID;
    }

    private void collectDeclared(Statement s, Set<String> out) {
        if (s instanceof VariableDeclaration vd) out.add(vd.name());
        else if (s instanceof BlockStatement bs)
            bs.statements().forEach(c -> collectDeclared(c, out));
    }

    private void collectVarNames(Statement s, Set<String> out) {
        if (s instanceof ExpressionStatement es) collectVarNamesInExpr(es.expression(), out);
        else if (s instanceof ReturnStatement rs && rs.value() != null)
            collectVarNamesInExpr(rs.value(), out);
        else if (s instanceof ThrowStatement ts && ts.expression() != null)
            collectVarNamesInExpr(ts.expression(), out);
        else if (s instanceof IfStatement i) {
            collectVarNamesInExpr(i.condition(), out);
            collectVarNames(i.thenBranch(), out);
            if (i.elseBranch() != null) collectVarNames(i.elseBranch(), out);
        } else if (s instanceof VariableDeclaration vd && vd.initializer() != null)
            collectVarNamesInExpr(vd.initializer(), out);
    }

    private void collectVarNamesInExpr(Expression e, Set<String> out) {
        if (e == null) return;
        if (e instanceof VarExpr v) out.add(v.name());
        else if (e instanceof BinExpr b) {
            collectVarNamesInExpr(b.left(), out);
            collectVarNamesInExpr(b.right(), out);
        } else if (e instanceof InvocationExpr inv) {
            if (inv.target() != null) collectVarNamesInExpr(inv.target(), out);
            for (Expression a : inv.arguments()) collectVarNamesInExpr(a, out);
        } else if (e instanceof FieldAccessExpr fa && fa.target() != null)
            collectVarNamesInExpr(fa.target(), out);
        else if (e instanceof AssignExpr a) {
            collectVarNamesInExpr(a.target(), out);
            collectVarNamesInExpr(a.value(), out);
        }
    }

    private boolean isBuiltin(String name) {
        return name.equals("null") || name.equals("this")
                || name.equals("true") || name.equals("false")
                || name.equals("super");
    }

    private Expression defaultVal(JavaType t) {
        if (t == null) return new VarExpr("null");
        return switch (t.kind()) {
            case INT, SHORT, BYTE, CHAR -> new LitExpr(0, JavaType.INT);
            case LONG -> new LitExpr(0L, JavaType.LONG);
            case FLOAT -> new LitExpr(0.0f, JavaType.FLOAT);
            case DOUBLE -> new LitExpr(0.0d, JavaType.DOUBLE);
            case BOOLEAN -> new LitExpr(false, JavaType.BOOLEAN);
            default -> new VarExpr("null");
        };
    }
}
