package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * instanceof 类型判断表达式:{@code 对象 instanceof 类型}.
 * <p>
 * 表示Java中的运行时类型检查操作,判断操作数对象是否为目标类型的实例.
 * </p>
 */
public final class InstanceOfExpr extends Expression {

    /** 被检查的操作数表达式 */
    private final Expression operand;

    /** 目标检查类型 */
    private final JavaType targetType;

    /**
     * 构造instanceof表达式.
     *
     * @param operand    被检查的操作数
     * @param targetType 目标类型
     */
    public InstanceOfExpr(Expression operand, JavaType targetType) {
        this.operand = operand;
        this.targetType = targetType;
    }

    /** @return 被检查的操作数 */
    public Expression operand() {return operand;}

    /** @return 目标类型 */
    public JavaType targetType() {return targetType;}

    @Override
    public AstKind kind() {return AstKind.INSTANCE_OF;}

    @Override
    public List<AstNode> children() {
        return operand != null ? List.of(operand) : List.of();
    }

    @Override
    public int precedence() {return 8;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
