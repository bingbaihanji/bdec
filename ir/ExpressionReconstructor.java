package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs complex expressions from flat IR sequences.
 *
 * Post-processes structured AST blocks to:
 * 1. Inline simple assignments (var = expr → replace var usages with expr)
 * 2. Chain arithmetic operations into expression trees
 * 3. Eliminate temporary variables created by stack-to-register translation
 */
public final class ExpressionReconstructor {

    /**
     * Reconstruct expressions in a block statement, returning an optimized version.
     */
    public BlockStatement reconstruct(BlockStatement block) {
        List<Statement> stmts = new ArrayList<>(block.statements());
        List<Statement> optimized = new ArrayList<>();

        // Map: variable name → expression it was assigned
        Map<String, Expression> inlineCandidates = new HashMap<>();
        // Count how many times each variable is used
        Map<String, Integer> useCount = new HashMap<>();

        // First pass: count variable uses
        for (Statement s : stmts) {
            countUses(s, useCount);
        }

        // Second pass: inline single-use assignments
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            Statement processed = tryInline(s, inlineCandidates, useCount);

            // If this is an assignment to a temp var used only once, remember it
            if (processed instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr assign
                    && assign.target() instanceof VarExpr targetVar
                    && isTempVar(targetVar.name())) {
                int uses = useCount.getOrDefault(targetVar.name(), 0);
                if (uses == 1) {
                    inlineCandidates.put(targetVar.name(), assign.value());
                    continue; // don't add this assignment to output
                }
            }

            optimized.add(processed);
        }

        return new BlockStatement(optimized);
    }

    /** Try to replace inline candidates in a statement. */
    private Statement tryInline(Statement stmt, Map<String, Expression> candidates,
                                Map<String, Integer> useCount) {
        if (stmt instanceof ExpressionStatement es) {
            return new ExpressionStatement(
                    inlineExpr(es.expression(), candidates));
        }
        if (stmt instanceof IfStatement ifStmt) {
            return new IfStatement(
                    inlineExpr(ifStmt.condition(), candidates),
                    tryInline(ifStmt.thenBranch(), candidates, useCount),
                    ifStmt.elseBranch() != null
                            ? tryInline(ifStmt.elseBranch(), candidates, useCount) : null);
        }
        if (stmt instanceof LoopStatement loop) {
            Expression cond = loop.condition() != null
                    ? inlineExpr(loop.condition(), candidates) : null;
            return new LoopStatement(loop.loopKind(), cond,
                    tryInline(loop.body(), candidates, useCount));
        }
        if (stmt instanceof BlockStatement block) {
            return reconstruct(block);
        }
        return stmt;
    }

    /** Recursively inline expressions. */
    private Expression inlineExpr(Expression expr, Map<String, Expression> candidates) {
        if (expr instanceof VarExpr v && candidates.containsKey(v.name())) {
            return candidates.get(v.name());
        }
        if (expr instanceof BinExpr bin) {
            return new BinExpr(bin.operator(),
                    inlineExpr(bin.left(), candidates),
                    inlineExpr(bin.right(), candidates));
        }
        if (expr instanceof UnExpr un) {
            return new UnExpr(un.operator(), inlineExpr(un.operand(), candidates));
        }
        if (expr instanceof AssignExpr assign) {
            return new AssignExpr(
                    inlineExpr(assign.target(), candidates),
                    inlineExpr(assign.value(), candidates));
        }
        return expr;
    }

    /** Count variable references in a statement subtree. */
    private void countUses(Statement stmt, Map<String, Integer> counts) {
        if (stmt instanceof ExpressionStatement es) {
            countUsesExpr(es.expression(), counts);
        } else if (stmt instanceof IfStatement ifStmt) {
            countUsesExpr(ifStmt.condition(), counts);
            countUses(ifStmt.thenBranch(), counts);
            if (ifStmt.elseBranch() != null) {
                countUses(ifStmt.elseBranch(), counts);
            }
        } else if (stmt instanceof LoopStatement loop) {
            if (loop.condition() != null) {
                countUsesExpr(loop.condition(), counts);
            }
            countUses(loop.body(), counts);
        } else if (stmt instanceof BlockStatement block) {
            for (Statement s : block.statements()) {
                countUses(s, counts);
            }
        }
    }

    private void countUsesExpr(Expression expr, Map<String, Integer> counts) {
        if (expr instanceof VarExpr v) {
            counts.merge(v.name(), 1, Integer::sum);
        } else if (expr instanceof BinExpr bin) {
            countUsesExpr(bin.left(), counts);
            countUsesExpr(bin.right(), counts);
        } else if (expr instanceof UnExpr un) {
            countUsesExpr(un.operand(), counts);
        } else if (expr instanceof AssignExpr assign) {
            countUsesExpr(assign.target(), counts);
            countUsesExpr(assign.value(), counts);
        } else if (expr instanceof CondExpr cond) {
            countUsesExpr(cond.condition(), counts);
            countUsesExpr(cond.trueExpr(), counts);
            countUsesExpr(cond.falseExpr(), counts);
        }
    }

    private boolean isTempVar(String name) {
        return name.startsWith("var") || name.startsWith("tmp");
    }
}
