package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 三元条件表达式:{@code 条件 ? 真值表达式 : 假值表达式}.
 * <p>
 * 表示Java中的唯一三元运算符(条件运算符),根据条件的真假选择返回两个分支表达式中的一个.
 * </p>
 */
public final class CondExpr extends Expression {

    /** 条件表达式 */
    private final Expression condition;

    /** 条件为真时的返回值表达式 */
    private final Expression trueExpr;

    /** 条件为假时的返回值表达式 */
    private final Expression falseExpr;

    /**
     * 构造三元条件表达式.
     *
     * @param c 条件表达式
     * @param t 真值分支表达式
     * @param f 假值分支表达式
     */
    public CondExpr(Expression c, Expression t, Expression f) {
        condition = c;
        trueExpr = t;
        falseExpr = f;
    }

    /** @return 条件表达式 */
    public Expression condition() {return condition;}

    /** @return 真值分支表达式 */
    public Expression trueExpr() {return trueExpr;}

    /** @return 假值分支表达式 */
    public Expression falseExpr() {return falseExpr;}

    @Override
    public AstKind kind() {return AstKind.CONDITIONAL;}

    @Override
    public List<AstNode> children() {return List.of(condition, trueExpr, falseExpr);}

    @Override
    public int precedence() {return 2;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
