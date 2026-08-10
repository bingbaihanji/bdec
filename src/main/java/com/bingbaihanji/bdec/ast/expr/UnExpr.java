package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 一元运算表达式.
 * <p>
 * 表示Java中的一元运算符操作,包括取反(-),逻辑非(!),按位取反(~),
 * 前置自增(++i),前置自减(--i),后置自增(i++)和后置自减(i--).
 * </p>
 */
public final class UnExpr extends Expression {

    /** 一元运算符 */
    private final UnaryOperator operator;

    /** 操作数表达式 */
    private final Expression operand;

    /**
     * 构造一元运算表达式.
     *
     * @param op 一元运算符
     * @param o  操作数
     */
    public UnExpr(UnaryOperator op, Expression o) {
        operator = op;
        operand = o;
    }

    /** @return 一元运算符 */
    public UnaryOperator operator() {return operator;}

    /** @return 操作数 */
    public Expression operand() {return operand;}

    @Override
    public AstKind kind() {return AstKind.UNARY;}

    @Override
    public List<AstNode> children() {return List.of(operand);}

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
