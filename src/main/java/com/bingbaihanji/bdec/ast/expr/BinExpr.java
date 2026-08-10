package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 二元运算表达式.
 * <p>
 * 表示形如 {@code left operator right} 的二元运算,包括算术运算(加减乘除取余),
 * 比较运算(等于,不等于,大于,小于等),逻辑运算(与,或)和位运算(与,或,异或,移位)等.
 * 运算符优先级由关联的 {@link BinaryOperator} 枚举值定义.
 * </p>
 */
public final class BinExpr extends Expression {

    /** 二元运算符 */
    private final BinaryOperator operator;

    /** 左操作数表达式 */
    private final Expression left;

    /** 右操作数表达式 */
    private final Expression right;

    /**
     * 构造二元运算表达式.
     *
     * @param op 二元运算符
     * @param l  左操作数
     * @param r  右操作数
     */
    public BinExpr(BinaryOperator op, Expression l, Expression r) {
        operator = op;
        left = l;
        right = r;
    }

    /** @return 二元运算符 */
    public BinaryOperator operator() {return operator;}

    /** @return 左操作数 */
    public Expression left() {return left;}

    /** @return 右操作数 */
    public Expression right() {return right;}

    @Override
    public AstKind kind() {return AstKind.BINARY;}

    @Override
    public List<AstNode> children() {return List.of(left, right);}

    @Override
    public int precedence() {return operator.precedence();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
