package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.*;

/**
 * Final safety-net cleanup that fixes common compile errors in decompiled output.
 * Handles: void returns in non-void methods, undeclared exception variables,
 * duplicate variable declarations, and undeclared variable uses.
 */
public class SourceCleanup implements RewriteRule {

    @Override public String name() {return "source-cleanup";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext ctx) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(cleanupType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration cleanupType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(cleanupMethod(md));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    private MethodDeclaration cleanupMethod(MethodDeclaration md) {
        if (md.body() == null) return md;
        boolean nonVoid = md.returnType() != null
                && md.returnType().kind() != TypeKind.VOID;
        JavaType retType = md.returnType();
        Set<String> declared = new HashSet<>();
        // Parameters are already declared
        if (md.parameterNames() != null) {
            for (String p : md.parameterNames()) declared.add(p);
        }
        Statement cleaned = fix(md.body(), declared, nonVoid, retType);
        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                md.parameterNames(), md.parameterTypes(), md.typeParameters(), cleaned);
    }

    /** Recursively fix compile errors in statements. */
    private Statement fix(Statement s, Set<String> declared, boolean nonVoid, JavaType retType) {
        if (s == null) return null;
        if (s instanceof VariableDeclaration vd) {
            declared.add(vd.name());
            return s;
        }
        if (s instanceof ReturnStatement rs && rs.value() != null) {
            Expression val = rs.value();
            if (isVoidCall(val) && nonVoid) {
                // "return voidCall();" → "voidCall(); return 0;"
                return new BlockStatement(List.of(
                        new ExpressionStatement(val),
                        new ReturnStatement(defaultVal(retType))));
            }
            return s;
        }
        if (s instanceof ThrowStatement ts
                && ts.expression() instanceof VarExpr v) {
            if (!declared.contains(v.name())) {
                // "throw var4;" → "Throwable var4; throw var4;"
                declared.add(v.name());
                return new BlockStatement(List.of(
                        new VariableDeclaration(
                                JavaType.classType("java/lang/Throwable"),
                                v.name(), null),
                        s));
            }
            return s;
        }
        if (s instanceof BlockStatement bs) {
            List<Statement> cleaned = new ArrayList<>();
            Set<String> blockDecl = new HashSet<>();
            for (Statement child : bs.statements()) {
                // Check for undeclared variable uses BEFORE processing
                List<Statement> preDecls = checkUndeclared(child, declared);
                cleaned.addAll(preDecls);
                // Fix duplicate declarations
                if (child instanceof VariableDeclaration vd) {
                    if (blockDecl.contains(vd.name())) {
                        // Duplicate → convert to assignment
                        if (vd.initializer() != null) {
                            cleaned.add(new ExpressionStatement(
                                    new AssignExpr(new VarExpr(vd.name()),
                                            vd.initializer())));
                        }
                        continue;
                    }
                    blockDecl.add(vd.name());
                    declared.add(vd.name());
                }
                cleaned.add(fix(child, declared, nonVoid, retType));
            }
            return new BlockStatement(cleaned);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    fix(i.thenBranch(), declared, nonVoid, retType),
                    i.elseBranch() != null
                            ? fix(i.elseBranch(), declared, nonVoid, retType) : null);
        }
        if (s instanceof LoopStatement l) {
            return new LoopStatement(l.loopKind(), l.condition(),
                    fix(l.body(), declared, nonVoid, retType));
        }
        if (s instanceof TryStatement t) {
            List<TryStatement.CatchClause> clauses = new ArrayList<>();
            for (TryStatement.CatchClause cc : t.catchClauses()) {
                declared.add(cc.varName());
                clauses.add(new TryStatement.CatchClause(cc.exceptionType(), cc.varName(),
                        fix(cc.body(), declared, nonVoid, retType)));
            }
            return new TryStatement(
                    fix(t.tryBody(), declared, nonVoid, retType),
                    clauses,
                    t.finallyBody() != null
                            ? fix(t.finallyBody(), declared, nonVoid, retType) : null);
        }
        if (s instanceof ExpressionStatement es) {
            checkUndeclaredInExpr(es.expression(), declared);
            return s;
        }
        return s;
    }

    /** Check for undeclared variable references and add declarations if needed.
     *  Returns statements to insert BEFORE the current statement. */
    private List<Statement> checkUndeclared(Statement s, Set<String> declared) {
        Set<String> refs = new HashSet<>();
        collectVarRefs(s, refs);
        List<Statement> result = new ArrayList<>();
        for (String name : refs) {
            if (!declared.contains(name) && !name.equals("null")
                    && !name.equals("this") && !name.equals("true")
                    && !name.equals("false") && !name.startsWith("/*")) {
                declared.add(name);
                result.add(new VariableDeclaration(
                        JavaType.classType("java/lang/Object"),
                        name, null));
            }
        }
        return result;
    }

    /** Collect variable names referenced in a statement tree. */
    private void collectVarRefs(Statement s, Set<String> refs) {
        if (s instanceof ExpressionStatement es) {
            collectVarRefsInExpr(es.expression(), refs);
        } else if (s instanceof ReturnStatement rs && rs.value() != null) {
            collectVarRefsInExpr(rs.value(), refs);
        } else if (s instanceof ThrowStatement ts && ts.expression() != null) {
            collectVarRefsInExpr(ts.expression(), refs);
        }
    }

    private void collectVarRefsInExpr(Expression e, Set<String> refs) {
        if (e instanceof VarExpr v) refs.add(v.name());
        if (e instanceof BinExpr b) {
            collectVarRefsInExpr(b.left(), refs);
            collectVarRefsInExpr(b.right(), refs);
        }
        if (e instanceof InvocationExpr inv) {
            if (inv.target() != null) collectVarRefsInExpr(inv.target(), refs);
            for (Expression arg : inv.arguments()) collectVarRefsInExpr(arg, refs);
        }
        if (e instanceof FieldAccessExpr fa && fa.target() != null) {
            collectVarRefsInExpr(fa.target(), refs);
        }
        if (e instanceof AssignExpr a) {
            collectVarRefsInExpr(a.target(), refs);
            collectVarRefsInExpr(a.value(), refs);
        }
    }

    private void checkUndeclaredInExpr(Expression e, Set<String> declared) {
        // no-op for now
    }

    private boolean isVoidCall(Expression e) {
        return e instanceof InvocationExpr inv
                && inv.returnType() != null
                && inv.returnType().kind() == TypeKind.VOID;
    }

    private Expression defaultVal(JavaType type) {
        if (type == null) return new VarExpr("null");
        return switch (type.kind()) {
            case INT, SHORT, BYTE, CHAR -> new LitExpr(0, JavaType.INT);
            case LONG -> new LitExpr(0L, JavaType.LONG);
            case FLOAT -> new LitExpr(0.0f, JavaType.FLOAT);
            case DOUBLE -> new LitExpr(0.0d, JavaType.DOUBLE);
            case BOOLEAN -> new LitExpr(false, JavaType.BOOLEAN);
            default -> new VarExpr("null");
        };
    }
}
