package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;

import java.util.List;
import java.util.Objects;

/**
 * 语句/表达式结构比较工具集合——从 {@link StatementUtils} 中提取的
 * 文本化与结构等价比较逻辑(里程碑 Phase 3).
 *
 * <p>包含语句文本化({@link #statementText},{@link #expressionText})
 * 与结构等价比较({@link #matchesAny},{@link #expressionsEquivalent}).
 * 保持无状态.</p>
 */
final class ComparisonUtils {

    private ComparisonUtils() {}

    /** 检查某条语句是否与候选列表中的任一语句在表达式结构上匹配 */
    static boolean matchesAny(Statement s, List<Statement> candidates) {
        if (s instanceof ExpressionStatement es) {
            for (Statement c : candidates) {
                if (c instanceof ExpressionStatement ce
                        && expressionsEquivalent(es.expression(), ce.expression())) {
                    return true;
                }
            }
        }
        if (s instanceof ReturnStatement rs && rs.value() != null) {
            for (Statement c : candidates) {
                if (c instanceof ReturnStatement rc && rc.value() != null
                        && expressionsEquivalent(rs.value(), rc.value())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 两个表达式树的结构化比较 */
    static boolean expressionsEquivalent(Expression a, Expression b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }

        switch (a) {
            case InvocationExpr ia when b instanceof InvocationExpr ib -> {
                if (!ia.methodName().equals(ib.methodName())) {
                    return false;
                }
                if (ia.arguments().size() != ib.arguments().size()) {
                    return false;
                }
                for (int i = 0; i < ia.arguments().size(); i++) {
                    if (!expressionsEquivalent(ia.arguments().get(i), ib.arguments().get(i))) {
                        return false;
                    }
                }
                return expressionsEquivalent(ia.target(), ib.target());
            }
            case LitExpr la when b instanceof LitExpr lb -> {
                Object va = la.value(), vb = lb.value();
                return Objects.equals(va, vb);
            }
            case VarExpr va when b instanceof VarExpr vb -> {
                return va.name().equals(vb.name());
            }
            case FieldAccessExpr fa when b instanceof FieldAccessExpr fb -> {
                return fa.fieldName().equals(fb.fieldName())
                        && expressionsEquivalent(fa.target(), fb.target());
            }
            default -> {
            }
        }
        return false;
    }

    /** Generate a stable text representation of a statement for comparison. */
    static String statementText(Statement s) {
        if (s instanceof ExpressionStatement es && es.expression() != null) {
            return "expr:" + expressionText(es.expression());
        }
        if (s instanceof ReturnStatement rs) {
            return "return:" + (rs.value() != null ? expressionText(rs.value()) : "void");
        }
        if (s instanceof VariableDeclaration vd) {
            return "var:" + vd.name() + ":" + (vd.initializer() != null ? expressionText(vd.initializer()) : "null");
        }
        if (s instanceof BlockStatement bs) {
            StringBuilder sb = new StringBuilder("block{");
            for (Statement cs : bs.statements()) {
                if (cs != null) {
                    sb.append(statementText(cs)).append(";");
                }
            }
            return sb.append("}").toString();
        }
        return s.getClass().getSimpleName() + "@" + System.identityHashCode(s);
    }

    static String expressionText(Expression e) {
        if (e instanceof InvocationExpr inv) {
            return "call:" + inv.methodName();
        }
        if (e instanceof VarExpr v) {
            return "var:" + v.name();
        }
        if (e instanceof LitExpr lit) {
            return "lit:" + lit.value();
        }
        if (e instanceof FieldAccessExpr fa) {
            return "field:" + fa.fieldName();
        }
        return e.getClass().getSimpleName();
    }
}
