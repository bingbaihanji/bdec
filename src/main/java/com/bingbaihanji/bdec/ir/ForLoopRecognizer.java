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
 * for循环识别器.
 * <p>
 * 在结构化AST中识别for循环模式,并在模式匹配时将while循环转换为for循环.
 * </p>
 *
 * <h3>检测的模式</h3>
 * <pre>{@code
 *   { init; while (cond) { body; incr; } }
 *   → for (init; cond; incr) { body; }
 * }</pre>
 * 同时还能检测基于迭代器的for-each模式.
 */
public final class ForLoopRecognizer {

    /**
     * 处理一个块语句,将匹配的while循环转换为for循环.
     *
     * @param block 原始块语句
     * @return 识别后的块语句
     */
    public BlockStatement recognize(BlockStatement block) {
        List<Statement> statements = new ArrayList<>(block.statements());
        List<Statement> result = new ArrayList<>();

        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);

            // 模式匹配:变量声明后紧跟while循环
            if (i + 1 < statements.size()
                    && isInitStatement(s)
                    && statements.get(i + 1) instanceof LoopStatement loop
                    && loop.loopKind() == LoopStatement.LoopKind.WHILE) {

                Expression init = extractInit(s);
                Statement body = loop.body();
                Expression incr = extractIncrement(body);
                Statement cleanBody = removeIncrement(body, incr);

                if (init != null && cleanBody != null) {
                    // 创建for循环: for(init; cond; incr) { cleanBody }
                    LoopStatement forLoop = new LoopStatement(
                            LoopStatement.LoopKind.FOR,
                            init,
                            loop.condition(),
                            incr,
                            cleanBody);
                    result.add(forLoop);
                    i++; // 跳过已处理的while循环
                    continue;
                }
            }

            // 递归处理嵌套块
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

    /**
     * 判断一条语句是否为循环初始化语句(变量赋值).
     */
    private boolean isInitStatement(Statement s) {
        if (s instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign) {
            return assign.target() instanceof VarExpr;
        }
        return false;
    }

    /**
     * 从语句中提取初始化表达式.
     */
    private Expression extractInit(Statement s) {
        if (s instanceof ExpressionStatement es) {
            return es.expression();
        }
        return null;
    }

    /**
     * 尝试从循环体末尾提取递增/递减表达式.
     */
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

    /**
     * 判断一个表达式是否为递增/递减操作(i++, ++i, i += n, i = i + n等).
     */
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

    /**
     * 从循环体中移除递增语句,返回清理后的循环体.
     */
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
            return new BlockStatement(List.of()); // 空循环体
        }
        return body;
    }

    /**
     * 递归处理if语句的两个分支.
     */
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
