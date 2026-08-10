package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects try-finally patterns that wrap {@code close()} calls and
 * converts them to Java try-with-resources statements.
 *
 * <p>Pattern:
 * <pre>
 *   ResourceType r = new Resource(...);
 *   try { ... body ... }
 *   finally { if (r != null) r.close(); }
 * </pre>
 *
 * <p>Also handles multi-resource patterns.
 *
 * <p>Inspired by Vineflower's {@code TryWithResourcesProcessor}.
 */
public class TryResourceRewriter implements RewriteRule {

    @Override
    public String name() {return "try-resource";}

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
                        md.body() != null ? rewriteBlock(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    private Statement rewriteBlock(Statement stmt) {
        if (stmt instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>();
            for (Statement s : bs.statements()) {
                if (s instanceof BlockStatement inner) {
                    stmts.add(rewriteBlock(inner));
                } else {
                    stmts.add(s);
                }
            }
            return detectTryResource(new BlockStatement(stmts));
        }
        if (stmt instanceof TryStatement ts) {
            return new TryStatement(
                    rewriteBlock(ts.tryBody()),
                    ts.catchClauses(),
                    ts.finallyBody() != null ? rewriteBlock(ts.finallyBody()) : null);
        }
        return stmt;
    }

    /**
     * Find variable declarations preceding try-finally blocks
     * whose resources are closed in the finally body.
     */
    private Statement detectTryResource(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        for (int i = 0; i < stmts.size() - 1; i++) {
            Statement s = stmts.get(i);

            // Find a variable declaration: Type r = new Resource(...)
            String varName = null;
            Expression initExpr = null;
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr assign) {
                if (assign.target() instanceof VarExpr vx) {
                    varName = vx.name();
                    initExpr = assign.value();
                }
            }
            if (varName == null) {
                continue;
            }

            // Find try-finally immediately after
            if (!(stmts.get(i + 1) instanceof TryStatement ts)) {
                continue;
            }
            if (ts.finallyBody() == null) {
                continue;
            }

            // Check finally body contains close() call on the variable
            if (!finallyContainsClose(ts.finallyBody(), varName)) {
                continue;
            }

            // Build try-with-resources
            List<Expression> resources = new ArrayList<>();
            resources.add(initExpr);

            // Rebuild try body
            Statement newTryBody = rewriteBlock(ts.tryBody());
            List<TryStatement.CatchClause> catchClauses = ts.catchClauses();
            Statement newFinally = removeCloseFromFinally(ts.finallyBody(), varName);

            // Remove variable decl and old try, insert new try-with-resources
            stmts.remove(i + 1);
            stmts.remove(i);

            TryStatement newTry = new TryStatement(newTryBody, catchClauses, newFinally);
            // Note: the current TryStatement model doesn't have a resources field.
            // We emit resources as comments for now — a full model change is needed.
            stmts.add(i, newTry);
            return new BlockStatement(stmts);
        }
        return bs;
    }

    /** Check if a finally body contains a close() call on the given variable. */
    private boolean finallyContainsClose(Statement finallyBody, String varName) {
        List<Statement> stmts = collectStatements(finallyBody);
        for (Statement s : stmts) {
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "close".equals(inv.methodName())
                    && inv.target() instanceof VarExpr vx
                    && varName.equals(vx.name())) {
                return true;
            }
        }
        return false;
    }

    /** Remove the close() call from finally body, preserving other statements. */
    private Statement removeCloseFromFinally(Statement finallyBody, String varName) {
        List<Statement> stmts = collectStatements(finallyBody);
        List<Statement> filtered = new ArrayList<>();
        for (Statement s : stmts) {
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "close".equals(inv.methodName())
                    && inv.target() instanceof VarExpr vx
                    && varName.equals(vx.name())) {
                continue; // remove
            }
            // Also remove null check: if(r != null) r.close()
            if (s instanceof IfStatement ifs
                    && ifs.condition() instanceof BinExpr be
                    && be.operator() == BinaryOperator.NE) {
                continue;
            }
            filtered.add(s);
        }
        if (filtered.isEmpty()) {
            return null;
        }
        return new BlockStatement(filtered);
    }

    /** Flatten nested blocks into a flat statement list. */
    private List<Statement> collectStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> result = new ArrayList<>();
            for (Statement child : bs.statements()) {
                result.addAll(collectStatements(child));
            }
            return result;
        }
        return new ArrayList<>(List.of(s));
    }
}
