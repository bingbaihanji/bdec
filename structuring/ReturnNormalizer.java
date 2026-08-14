package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * return 归一化工具集合——从 {@link StatementUtils} 中提取的分支返回
 * 归一化逻辑(里程碑 Phase 3).
 *
 * <p>包含 return 检测({@link #hasReturnStmt}),孤儿表达式剥离
 * ({@link #stripOrphanExprs}),分支体 return 包装({@link #wrapAsReturn})
 * 与布尔字面量归一化({@link #boolLiteral}). 保持无状态.</p>
 */
final class ReturnNormalizer {

    private ReturnNormalizer() {}

    /** 检查语句树中是否包含 ReturnStatement */
    static boolean hasReturnStmt(Statement s) {
        if (s instanceof ReturnStatement) {
            return true;
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream().anyMatch(ReturnNormalizer::hasReturnStmt);
        }
        // 递归检查可能包含 return 的复合语句
        if (s instanceof IfStatement i) {
            return hasReturnStmt(i.thenBranch())
                    || (i.elseBranch() != null && hasReturnStmt(i.elseBranch()));
        }
        if (s instanceof LoopStatement l) {
            return hasReturnStmt(l.body());
        }
        if (s instanceof TryStatement t) {
            boolean inTry = hasReturnStmt(t.tryBody());
            boolean inCatch = t.catchClauses().stream()
                    .anyMatch(cc -> hasReturnStmt(cc.body()));
            boolean inFinally = t.finallyBody() != null
                    && hasReturnStmt(t.finallyBody());
            return inTry || inCatch || inFinally;
        }
        return false;
    }

    /** 从已包含 ReturnStatement 的分支体中剥离孤立的 ExpressionStatement.
     *  这些语句通常是合并点处的块排序噪声,无实际意义. */
    static Statement stripOrphanExprs(Statement s) {
        if (s instanceof BlockStatement bs) {
            boolean hasAnyReturn = bs.statements().stream().anyMatch(ReturnNormalizer::hasReturnStmt);
            if (!hasAnyReturn) {
                return s;
            }
            List<Statement> filtered = new ArrayList<>();
            for (Statement child : bs.statements()) {
                // 仅剥离无意义的 ExpressionStatement(变量引用,临时变量引用),
                // 不剥离具有真正副作用的表达式(如字段赋值)
                if (child instanceof ExpressionStatement es
                        && StatementUtils.isIgnorableExpr(es.expression())) {
                    continue; // 剥离孤立的 CONST/temp
                }
                if (child instanceof BlockStatement) {
                    Statement stripped = stripOrphanExprs(child);
                    if (!StatementUtils.isEmptyBlock(stripped)) {
                        filtered.add(stripped);
                    }
                } else {
                    filtered.add(child);
                }
            }
            if (filtered.isEmpty()) {
                return new BlockStatement(List.of());
            }
            if (filtered.size() == 1) {
                return filtered.getFirst();
            }
            return new BlockStatement(filtered);
        }
        return s;
    }

    /** 将分支体中的 ExpressionStatement 包装为 ReturnStatement
     * (处理没有自身 RETURN 的分支中的孤立的 CONST).
     *  跳过 void 表达式(例如孤立的 lock.unlock() 调用)以避免
     *  "void cannot be converted to int" 编译错误. */
    static Statement wrapAsReturn(Statement s, boolean isBoolRet, boolean isVoidRet) {
        if (s instanceof BlockStatement bs) {
            if (hasReturnStmt(s)) {
                return s; // 已有 RETURN
            }
            List<Statement> result = new ArrayList<>();
            boolean addedReturn = false;
            for (Statement child : bs.statements()) {
                if (child instanceof ExpressionStatement es) {
                    Expression e = es.expression();
                    // 保留 void 方法调用,赋值,非 void 方法调用(其结果可能
                    // 被后续 STORE 消费)和字段访问原样,不包装为 return.
                    // 只有简单值(常量,变量,转换)才包装为 return.
                    if (StatementUtils.isVoidExpr(e) || StatementUtils.isAssignExpr(e)
                            || e instanceof InvocationExpr
                            || e instanceof FieldAccessExpr) {
                        result.add(child); // 保留原样
                    } else {
                        result.add(new ReturnStatement(boolLiteral(e, isBoolRet)));
                        addedReturn = true;
                    }
                } else if (child instanceof BlockStatement inner) {
                    Statement wrapped = wrapAsReturn(inner, isBoolRet, isVoidRet);
                    result.add(wrapped);
                    if (hasReturnStmt(wrapped)) {
                        addedReturn = true;
                    }
                } else {
                    result.add(child);
                    if (child instanceof ReturnStatement) {
                        addedReturn = true;
                    }
                }
            }
            // 如果未添加任何 return,追加一个合成 return 以确保方法可编译
            // void 方法使用 return; (无值),boolean 方法使用 return false;,其他使用 return null;
            if (!addedReturn) {
                if (isVoidRet) {
                    result.add(new ReturnStatement(null));
                } else {
                    result.add(new ReturnStatement(isBoolRet
                            ? new LitExpr(false, JavaType.BOOLEAN)
                            : new LitExpr(null,
                            JavaType.classType("java/lang/Object"))));
                }
            }
            if (result.isEmpty()) {
                return new BlockStatement(List.of());
            }
            if (result.size() == 1) {
                return result.getFirst();
            }
            return new BlockStatement(result);
        }
        if (s instanceof ExpressionStatement es) {
            if (StatementUtils.isVoidExpr(es.expression())
                    || StatementUtils.isAssignExpr(es.expression())) {
                // 对 void 方法使用 return;(无值),否则追加合成 null/0/false return
                if (isVoidRet) {
                    return new BlockStatement(List.of(s, new ReturnStatement(null)));
                }
                return new BlockStatement(List.of(
                        s,
                        new ReturnStatement(isBoolRet
                                ? new LitExpr(false, JavaType.BOOLEAN)
                                : new LitExpr(null, JavaType.classType("java/lang/Object")))));
            }
            return new ReturnStatement(boolLiteral(es.expression(), isBoolRet));
        }
        return s;
    }

    /** 对 boolean 返回方法,将整数字面量转为布尔值 */
    static Expression boolLiteral(Expression e, boolean isBoolRet) {
        if (isBoolRet && e instanceof LitExpr lit && lit.value() instanceof Integer i) {
            return new LitExpr(i != 0, JavaType.BOOLEAN);
        }
        return e;
    }
}
