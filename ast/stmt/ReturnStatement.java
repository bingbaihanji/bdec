package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/**
 * 返回语句节点,表示方法中的 {@code return} 语句.
 *
 * <p>return 语句可带返回值(return expression)或不带返回值(return;).
 * value 为 null 时表示无返回值的 return 语句.
 */
public final class ReturnStatement extends Statement {

    /** 返回值表达式,可为 null 表示仅 return; */
    private final Expression value;

    /**
     * 构造一个返回语句.
     *
     * @param value 返回值表达式,可为 null
     */
    public ReturnStatement(Expression value) {this.value = value;}

    /** @return 返回值表达式,可为 null */
    public Expression value() {return value;}

    @Override
    public AstKind kind() {return AstKind.RETURN;}

    @Override
    public List<AstNode> children() {return value != null ? List.of(value) : List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
