package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses if-else statements that assign to the same variable
 * or return from both branches into ternary ({@code ?:}) expressions.
 *
 * <p>Patterns:
 * <pre>
 *   if(cond) x = a; else x = b;  →  x = cond ? a : b;
 *   if(cond) return a; else return b;  →  return cond ? a : b;
 * </pre>
 *
 * <p>Inspired by CFR's {@code ConditionalRewriter}.
 */
public class TernaryRewriter implements RewriteRule {

    @Override
    public String name() { return "ternary"; }

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> rewrittenTypes = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            rewrittenTypes.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), rewrittenTypes);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> rewrittenMembers = new ArrayList<>();
        for (AstNode member : td.children()) {
            if (member instanceof MethodDeclaration md) {
                Statement newBody = rewriteStatement(md.body());
                rewrittenMembers.add(new MethodDeclaration(
                        md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(), newBody));
            } else {
                rewrittenMembers.add(member);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), rewrittenMembers);
    }

    private Statement rewriteStatement(Statement stmt) {
        if (stmt instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement s : bs.statements()) {
                rewritten.add(rewriteStatement(s));
            }
            return collapseTernaries(new BlockStatement(rewritten));
        }
        if (stmt instanceof IfStatement ifStmt) {
            Statement thenBody = rewriteStatement(ifStmt.thenBranch());
            Statement elseBody = ifStmt.elseBranch() != null
                    ? rewriteStatement(ifStmt.elseBranch()) : null;
            // Try direct ternary collapse
            Expression ternary = tryCollapse(ifStmt.condition(), thenBody, elseBody);
            if (ternary != null) {
                return new ExpressionStatement(ternary);
            }
            // Try return ternary: if(cond) return a; else return b;
            ternary = tryReturnTernary(ifStmt.condition(), thenBody, elseBody);
            if (ternary != null) {
                return new ReturnStatement(ternary);
            }
            return new IfStatement(ifStmt.condition(), thenBody, elseBody);
        }
        return stmt;
    }

    /** Walk a BlockStatement and collapse adjacent ternary-pattern if-elses. */
    private Statement collapseTernaries(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < stmts.size(); i++) {
                Statement s = stmts.get(i);
                if (s instanceof IfStatement ifStmt && ifStmt.elseBranch() != null) {
                    // Try assign-ternary
                    Expression ternary = tryCollapse(ifStmt.condition(),
                            ifStmt.thenBranch(), ifStmt.elseBranch());
                    if (ternary != null) {
                        stmts.set(i, new ExpressionStatement(ternary));
                        changed = true;
                        break;
                    }
                    // Try return-ternary
                    ternary = tryReturnTernary(ifStmt.condition(),
                            ifStmt.thenBranch(), ifStmt.elseBranch());
                    if (ternary != null) {
                        stmts.set(i, new ReturnStatement(ternary));
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return new BlockStatement(stmts);
    }

    /**
     * Try to collapse if-else into assign-ternary: x = cond ? a : b.
     */
    private Expression tryCollapse(Expression cond, Statement thenBody, Statement elseBody) {
        if (elseBody == null) return null;
        Expression thenExpr = singleExpr(thenBody);
        Expression elseExpr = singleExpr(elseBody);
        if (thenExpr == null || elseExpr == null) return null;

        // Case: both branches are assignments to the same variable
        if (thenExpr instanceof AssignExpr ta && elseExpr instanceof AssignExpr ea) {
            if (ta.target() instanceof VarExpr tv && ea.target() instanceof VarExpr ev) {
                if (tv.name().equals(ev.name())) {
                    return new AssignExpr(ta.target(),
                            new CondExpr(cond, ta.value(), ea.value()),
                            ta.compoundOp()); // preserve compound assignment operator
                }
            }
        }
        return null;
    }

    /**
     * Try to collapse if-else into return-ternary: return cond ? a : b.
     */
    private Expression tryReturnTernary(Expression cond, Statement thenBody, Statement elseBody) {
        if (elseBody == null) return null;

        Statement thenStmt = thenBody;
        Statement elseStmt = elseBody;
        if (thenBody instanceof BlockStatement tb && tb.statements().size() == 1)
            thenStmt = tb.statements().getFirst();
        if (elseBody instanceof BlockStatement eb && eb.statements().size() == 1)
            elseStmt = eb.statements().getFirst();

        if (thenStmt instanceof ReturnStatement tr
                && elseStmt instanceof ReturnStatement er) {
            Expression thenVal = tr.value();
            Expression elseVal = er.value();
            if (thenVal == null) thenVal = new VarExpr("/*void*/");
            if (elseVal == null) elseVal = new VarExpr("/*void*/");
            return new CondExpr(cond, thenVal, elseVal);
        }
        return null;
    }

    /** Extract the single expression from a statement, unwrapping single-statement blocks. */
    private Expression singleExpr(Statement s) {
        if (s instanceof ExpressionStatement es) return es.expression();
        if (s instanceof BlockStatement bs && bs.statements().size() == 1)
            return singleExpr(bs.statements().getFirst());
        return null;
    }
}
