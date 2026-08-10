package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/**
 * 表达式语句节点,将表达式包装为可执行的语句.
 *
 * <p>典型用法包括方法调用,赋值表达式,自增/自减等以分号结尾的表达式.
 */
public final class ExpressionStatement extends Statement {

    /** 被包装的表达式 */
    private final Expression expression;

    /**
     * 构造一个表达式语句.
     *
     * @param expr 被包装的表达式
     */
    public ExpressionStatement(Expression expr) {this.expression = expr;}

    /** @return 被包装的表达式 */
    public Expression expression() {return expression;}

    @Override
    public AstKind kind() {return AstKind.EXPRESSION_STMT;}

    @Override
    public List<AstNode> children() {return List.of(expression);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
