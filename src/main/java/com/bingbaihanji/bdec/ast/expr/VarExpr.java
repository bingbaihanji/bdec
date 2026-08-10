package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 变量引用表达式.
 * <p>
 * 表示Java中对变量名的引用,包括局部变量,方法参数,字段和静态字段的引用.
 * 变量表达式是叶子节点,没有子节点.
 * </p>
 */
public final class VarExpr extends Expression {

    /** 变量名称 */
    private final String name;

    /**
     * 构造变量引用表达式.
     *
     * @param n 变量名称
     */
    public VarExpr(String n) {name = n;}

    /** @return 变量名称 */
    public String name() {return name;}

    @Override
    public AstKind kind() {return AstKind.VARIABLE;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
