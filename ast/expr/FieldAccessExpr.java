package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 字段访问表达式:{@code 对象.字段} 或 {@code 类.静态字段}.
 * <p>
 * 表示Java中的字段访问操作,包括实例字段访问和静态字段访问.
 * 当目标表达式为null时表示当前实例的字段访问(隐式this).
 * </p>
 */
public final class FieldAccessExpr extends Expression {

    /** 字段访问的目标对象表达式,静态字段访问或隐式this访问时为null */
    private final Expression target;

    /** 被访问的字段名称 */
    private final String fieldName;

    /**
     * 构造字段访问表达式.
     *
     * @param target    目标对象表达式(可为null)
     * @param fieldName 字段名称
     */
    public FieldAccessExpr(Expression target, String fieldName) {
        this.target = target;
        this.fieldName = fieldName;
    }

    /** @return 目标对象表达式 */
    public Expression target() {return target;}

    /** @return 字段名称 */
    public String fieldName() {return fieldName;}

    @Override
    public AstKind kind() {return AstKind.FIELD_ACCESS;}

    @Override
    public List<AstNode> children() {
        return target != null ? List.of(target) : List.of();
    }

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
