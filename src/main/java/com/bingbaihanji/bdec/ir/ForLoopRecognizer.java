package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognizes for-loop patterns in structured AST and converts
 * while-loops to for-loops when the pattern matches.
 *
 * Pattern detected:
 *   { init; while (cond) { body; incr; } }
 *   → for (init; cond; incr) { body; }
 *
 * Also detects for-each patterns (iterator-based iteration).
 */
public final class ForLoopRecognizer {

    /**
     * Process a block and convert matching while-loops to for-loops.
     */
    public BlockStatement recognize(BlockStatement block) {
        List<Statement> statements = new ArrayList<>(block.statements());
        List<Statement> result = new ArrayList<>();

        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);

            // Pattern: variable declaration followed by while loop
            if (i + 1 < statements.size()
                    && isInitStatement(s)
                    && statements.get(i + 1) instanceof LoopStatement loop
                    && loop.loopKind() == LoopStatement.LoopKind.WHILE) {

                Expression init = extractInit(s);
                Statement body = loop.body();
                Expression incr = extractIncrement(body);
                Statement cleanBody = removeIncrement(body, incr);

                if (init != null && cleanBody != null) {
                    // Create for-loop: for(init; cond; incr) { cleanBody }
                    LoopStatement forLoop = new LoopStatement(
                            LoopStatement.LoopKind.FOR,
                            loop.condition(),
                            cleanBody);
                    result.add(forLoop);
                    i++; // skip the while loop
                    continue;
                }
            }

            // Recursive processing for nested blocks
            if (s instanceof BlockStatement bs) {
                s = recognize(bs);
            } else if (s instanceof IfStatement ifStmt) {
                s = recognizeIf(ifStmt);
            } else if (s instanceof LoopStatement loop) {
                if (loop.body() instanceof BlockStatement bs) {
                    s = new LoopStatement(loop.loopKind(), loop.condition(), recognize(bs));
                }
            }

            result.add(s);
        }

        return new BlockStatement(result);
    }

    /** Check if a statement is a loop initializer (variable assignment). */
    private boolean isInitStatement(Statement s) {
        if (s instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign) {
            return assign.target() instanceof VarExpr;
        }
        return false;
    }

    /** Extract the initialization expression from a statement. */
    private Expression extractInit(Statement s) {
        if (s instanceof ExpressionStatement es) {
            return es.expression();
        }
        return null;
    }

    /** Try to extract an increment statement from the end of a loop body. */
    private Expression extractIncrement(Statement body) {
        if (body instanceof BlockStatement block) {
            List<Statement> stmts = block.statements();
            if (stmts.isEmpty()) {
                return null;
            }
            Statement last = stmts.getLast();
            if (last instanceof ExpressionStatement es) {
                Expression expr = es.expression();
                if (isIncrementExpr(expr)) {
                    return expr;
                }
            }
        } else if (body instanceof ExpressionStatement es) {
            if (isIncrementExpr(es.expression())) {
                return es.expression();
            }
        }
        return null;
    }

    /** Check if an expression is an increment operation (i++, ++i, i += n, i = i + n). */
    private boolean isIncrementExpr(Expression e) {
        if (e instanceof AssignExpr assign) {
            if (assign.target() instanceof VarExpr) {
                return true;
            }
        }
        if (e instanceof UnExpr un) {
            UnaryOperator op = un.operator();
            return op == UnaryOperator.POST_INC || op == UnaryOperator.PRE_INC
                    || op == UnaryOperator.POST_DEC || op == UnaryOperator.PRE_DEC;
        }
        return false;
    }

    /** Remove the increment statement from the body, returning the cleaned body. */
    private Statement removeIncrement(Statement body, Expression incr) {
        if (incr == null) {
            return body;
        }
        if (body instanceof BlockStatement block) {
            List<Statement> stmts = new ArrayList<>(block.statements());
            if (!stmts.isEmpty()) {
                Statement last = stmts.getLast();
                if (last instanceof ExpressionStatement es
                        && es.expression().equals(incr)) {
                    stmts.removeLast();
                }
            }
            return new BlockStatement(stmts);
        }
        if (body instanceof ExpressionStatement es && es.expression().equals(incr)) {
            return new BlockStatement(List.of()); // empty body
        }
        return body;
    }

    /** Recursively process if-statement branches. */
    private Statement recognizeIf(IfStatement ifStmt) {
        Statement thenBranch = ifStmt.thenBranch();
        if (thenBranch instanceof BlockStatement bs) {
            thenBranch = recognize(bs);
        }
        Statement elseBranch = ifStmt.elseBranch();
        if (elseBranch instanceof BlockStatement bs) {
            elseBranch = recognize(bs);
        }
        return new IfStatement(ifStmt.condition(), thenBranch, elseBranch);
    }
}
