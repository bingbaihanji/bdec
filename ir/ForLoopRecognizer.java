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
import com.bingbaihanji.bdec.type.JavaType;

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

            // 模式匹配:变量声明后紧跟while循环.
            // 仅当循环体含 continue 时转换——while 形式的增量在体底部会被
            // continue 跳过(死循环),for 形式 continue 会执行增量;无 continue
            // 的循环 while 形式语义正确,保持 while(既有输出/测试).
            if (i + 1 < statements.size()
                    && isInitStatement(s)
                    && statements.get(i + 1) instanceof LoopStatement loop
                    && loop.loopKind() == LoopStatement.LoopKind.WHILE
                    && containsContinue(loop.body())) {

                Expression init = extractInit(s);
                Statement body = loop.body();
                Expression incr = extractIncrement(body);

                // 增量必须作用于循环变量(与 init 同变量):
                // 否则如 while(j<n){ s += j++; } 的 s += j++ 会被误当 for 增量
                // 并被 removeIncrement 误删(isIncrementExpr 只判增量形态不判目标).
                if (init != null && incr != null
                        && !sameTargetVariable(init, incr)) {
                    incr = null;
                }
                Statement cleanBody = removeIncrement(body, incr);

                if (init != null && cleanBody != null) {
                    // 声明初始化(int i = 0)保留在 for 之前(它是声明语句,
                    // for 的 init 只能表达 i = 0 赋值),否则 i 未声明.
                    if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration) {
                        result.add(s);
                    }
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
        // int i = 0; 形式的声明初始化(反编译器常用声明而非赋值语句)
        return s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
    }

    /** 语句树中是否包含 continue. */
    private boolean containsContinue(Statement s) {
        if (s == null) {
            return false;
        }
        if (s.kind() == com.bingbaihanji.bdec.ast.AstKind.CONTINUE) {
            return true;
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream().anyMatch(this::containsContinue);
        }
        if (s instanceof IfStatement ifs) {
            return containsContinue(ifs.thenBranch())
                    || (ifs.elseBranch() != null && containsContinue(ifs.elseBranch()));
        }
        if (s instanceof LoopStatement loop) {
            return containsContinue(loop.body());
        }
        return false;
    }

    /** 初始化与增量是否作用于同一循环变量. */
    private boolean sameTargetVariable(Expression init, Expression incr) {
        String initVar = targetVariable(init);
        String incrVar = targetVariable(incr);
        return initVar != null && initVar.equals(incrVar);
    }

    /** 表达式作用的目标变量名(j = 0 / j++ / j += 1 → "j"),无法识别返回 null. */
    private String targetVariable(Expression e) {
        if (e instanceof AssignExpr assign && assign.target() instanceof VarExpr v) {
            return v.name();
        }
        if (e instanceof UnExpr un && un.operand() instanceof VarExpr v) {
            return v.name();
        }
        return null;
    }

    /**
     * 从语句中提取初始化表达式.
     */
    private Expression extractInit(Statement s) {
        if (s instanceof ExpressionStatement es) {
            return es.expression();
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd) {
            // int i = 0 → for 的 init 用 i = 0(声明语句保留在 for 之前,
            // 冗余但语义正确;continue 时 for 会执行增量).
            return new AssignExpr(new com.bingbaihanji.bdec.ast.expr.VarExpr(vd.name()),
                    vd.initializer() != null ? vd.initializer()
                            : new com.bingbaihanji.bdec.ast.expr.LitExpr(0, JavaType.INT));
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
