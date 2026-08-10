package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/**
 * throw 语句节点,表示 Java 中的异常抛出语句.
 *
 * <p>对应 Java 语法中的 {@code throw expr;} 结构.
 * expression 字段表示被抛出的异常对象表达式.
 */
public final class ThrowStatement extends Statement {

    /** 被抛出的异常表达式 */
    private final Expression expression;

    /**
     * 构造一个 throw 语句.
     *
     * @param expression 被抛出的异常表达式
     */
    public ThrowStatement(Expression expression) {
        this.expression = expression;
    }

    /** @return 被抛出的异常表达式 */
    public Expression expression() {return expression;}

    @Override
    public AstKind kind() {return AstKind.THROW;}

    @Override
    public List<AstNode> children() {
        return expression != null ? List.of(expression) : List.of();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
