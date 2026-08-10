package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 赋值表达式.
 * <p>
 * 支持普通赋值({@code =})和复合赋值({@code +=},{@code |=},{@code >>>=} 等).
 * 通过可选的 {@link #compoundOp} 字段区分普通赋值与复合赋值.
 * </p>
 */
public final class AssignExpr extends Expression {

    /** 赋值目标表达式(左值) */
    private final Expression target;

    /** 赋值源值表达式(右值) */
    private final Expression value;

    /** 复合赋值运算符,普通赋值时为null */
    private final BinaryOperator compoundOp;

    /**
     * 构造普通赋值表达式({@code target = value}).
     *
     * @param t 赋值目标
     * @param v 赋值值
     */
    public AssignExpr(Expression t, Expression v) {
        this(t, v, null);
    }

    /**
     * 构造赋值表达式,可包含复合赋值运算符.
     *
     * @param t          赋值目标
     * @param v          赋值值
     * @param compoundOp 复合赋值运算符(如无复合操作则为null)
     */
    public AssignExpr(Expression t, Expression v, BinaryOperator compoundOp) {
        target = t;
        value = v;
        this.compoundOp = compoundOp;
    }

    /** @return 赋值目标表达式 */
    public Expression target() {return target;}

    /** @return 赋值源值表达式 */
    public Expression value() {return value;}

    /**
     * 获取复合赋值运算符.
     *
     * @return null表示普通{@code =};非null表示复合赋值(如{@code |=})
     */
    public BinaryOperator compoundOp() {return compoundOp;}

    @Override
    public AstKind kind() {return AstKind.ASSIGNMENT;}

    @Override
    public List<AstNode> children() {return List.of(target, value);}

    @Override
    public int precedence() {return 1;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
