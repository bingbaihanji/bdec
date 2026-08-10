package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 数组元素访问表达式:{@code 数组[索引]}.
 * <p>
 * 涵盖数组元素的读取操作(如 {@code a[i]})和赋值操作的左值部分(如 {@code a[i] = x}).
 * 数组引用和索引值均为子表达式.
 * </p>
 */
public final class ArrayAccessExpr extends Expression {

    /** 数组表达式(被访问的数组对象) */
    private final Expression array;

    /** 索引表达式(数组下标) */
    private final Expression index;

    /**
     * 构造数组访问表达式.
     *
     * @param array 数组表达式
     * @param index 索引表达式
     */
    public ArrayAccessExpr(Expression array, Expression index) {
        this.array = array;
        this.index = index;
    }

    /** @return 数组表达式 */
    public Expression array() {return array;}

    /** @return 索引表达式 */
    public Expression index() {return index;}

    @Override
    public AstKind kind() {return AstKind.ARRAY_ACCESS;}

    @Override
    public List<AstNode> children() {
        return array != null && index != null
                ? List.of(array, index) : array != null
                ? List.of(array) : List.of();
    }

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
