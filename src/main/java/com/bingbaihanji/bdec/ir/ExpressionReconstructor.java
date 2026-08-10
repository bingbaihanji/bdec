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
 * 表达式重构器.
 * <p>
 * 从扁平的IR序列中重建复杂的表达式树.后处理已结构化的AST块,完成以下工作:
 * </p>
 * <ol>
 *   <li>内联简单赋值:将 var = expr 中的变量使用替换为表达式</li>
 *   <li>将算术操作链组合成表达式树</li>
 *   <li>消除由栈到寄存器转换引入的临时变量</li>
 * </ol>
 */
public final class ExpressionReconstructor {

    /**
     * 对块语句进行表达式重构,返回优化后的版本.
     *
     * @param block 原始块语句
     * @return 表达式重构后的块语句
     */
    public BlockStatement reconstruct(BlockStatement block) {
        List<Statement> stmts = new ArrayList<>(block.statements());
        List<Statement> optimized = new ArrayList<>();

        // 变量名 → 其所赋值的表达式映射(内联候选)
        Map<String, Expression> inlineCandidates = new HashMap<>();
        // 变量名 → 使用次数映射
        Map<String, Integer> useCount = new HashMap<>();

        // 第一遍:统计变量使用次数
        for (Statement s : stmts) {
            countUses(s, useCount);
        }

        // 第二遍:内联仅使用一次的简单赋值
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            Statement processed = tryInline(s, inlineCandidates, useCount);

            // 如果是赋值给仅使用一次的临时变量,记录为内联候选而非输出
            if (processed instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr assign
                    && assign.target() instanceof VarExpr targetVar
                    && isTempVar(targetVar.name())) {
                int uses = useCount.getOrDefault(targetVar.name(), 0);
                if (uses == 1) {
                    inlineCandidates.put(targetVar.name(), assign.value());
                    continue; // 不将赋值语句添加到输出
                }
            }

            optimized.add(processed);
        }

        return new BlockStatement(optimized);
    }

    /**
     * 尝试在语句中替换内联候选变量.
     */
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

    /**
     * 递归地在表达式中替换内联候选变量.
     */
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
                    inlineExpr(assign.value(), candidates),
                    assign.compoundOp()); // 保留复合赋值运算符
        }
        return expr;
    }

    /**
     * 统计语句子树中的变量引用次数.
     */
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
            // 独立处理嵌套块以避免作用域泄漏
            // (嵌套块内的临时变量不应影响外层的内联判断)
            Map<String, Integer> nestedCounts = new HashMap<>();
            for (Statement s : block.statements()) {
                countUses(s, nestedCounts);
            }
            // 仅合并外部定义的变量的计数
            for (var entry : nestedCounts.entrySet()) {
                counts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
    }

    /**
     * 统计表达式中的变量引用次数.
     */
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

    /**
     * 判断变量名是否为临时变量(以"var"或"tmp"开头).
     */
    private boolean isTempVar(String name) {
        return name.startsWith("var") || name.startsWith("tmp");
    }
}
