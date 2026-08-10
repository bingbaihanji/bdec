package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * 类型转换表达式:{@code (目标类型) 操作数}.
 * <p>
 * 表示Java中的强制类型转换操作,将操作数表达式的类型转换为指定的目标类型.
 * </p>
 */
public final class CastExpr extends Expression {

    /** 转换的目标类型 */
    private final JavaType targetType;

    /** 被转换的操作数表达式 */
    private final Expression operand;

    /**
     * 构造类型转换表达式.
     *
     * @param targetType 转换目标类型
     * @param operand    被转换的操作数
     */
    public CastExpr(JavaType targetType, Expression operand) {
        this.targetType = targetType;
        this.operand = operand;
    }

    /** @return 转换目标类型 */
    public JavaType targetType() {return targetType;}

    /** @return 被转换的操作数 */
    public Expression operand() {return operand;}

    @Override
    public AstKind kind() {return AstKind.CAST;}

    @Override
    public List<AstNode> children() {return List.of(operand);}

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
