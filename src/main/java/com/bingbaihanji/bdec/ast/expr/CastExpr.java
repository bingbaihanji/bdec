package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;
import java.util.Map;

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

    /** JSR-308 类型注解(类型路径 → 渲染后注解行列表),来自 0x43 CAST 目标 */
    private final Map<List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
            List<String>> typeAnnotations;

    /**
     * 构造类型转换表达式.
     *
     * @param targetType 转换目标类型
     * @param operand    被转换的操作数
     */
    public CastExpr(JavaType targetType, Expression operand) {
        this(targetType, operand, Map.of());
    }

    /**
     * 构造带 JSR-308 类型注解的类型转换表达式.
     *
     * @param targetType      转换目标类型
     * @param operand         被转换的操作数
     * @param typeAnnotations 类型路径 → 渲染后注解行列表
     */
    public CastExpr(JavaType targetType, Expression operand,
                    Map<List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                            List<String>> typeAnnotations) {
        this.targetType = targetType;
        this.operand = operand;
        this.typeAnnotations = typeAnnotations == null ? Map.of() : typeAnnotations;
    }

    /** @return 转换目标类型 */
    public JavaType targetType() {return targetType;}

    /** @return 被转换的操作数 */
    public Expression operand() {return operand;}

    /** @return JSR-308 类型注解(类型路径 → 渲染后注解行列表) */
    public Map<List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
            List<String>> typeAnnotations() {return typeAnnotations;}

    @Override
    public AstKind kind() {return AstKind.CAST;}

    @Override
    public List<AstNode> children() {return List.of(operand);}

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
