package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * 字面量表达式.
 * <p>
 * 表示Java中的字面量值,包括整数,浮点数,布尔值,字符,字符串和null.
 * 字面量表达式没有子节点(叶子节点).
 * </p>
 */
public final class LitExpr extends Expression {

    /** 字面量值(可为Number,Boolean,Character,String或null) */
    private final Object value;

    /** 字面量的Java类型 */
    private final JavaType type;

    /**
     * 构造字面量表达式.
     *
     * @param v 字面量值
     * @param t Java类型
     */
    public LitExpr(Object v, JavaType t) {
        value = v;
        type = t;
    }

    /** @return 字面量值 */
    public Object value() {return value;}

    /** @return 字面量的 Java 类型 */
    public JavaType type() {return type;}

    @Override
    public AstKind kind() {return AstKind.LITERAL;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
