package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * 字段声明节点,表示类或接口中的成员变量声明.
 *
 * <p>对应 Java 中的字段声明,如 {@code private static final int x = 5;}.
 * 包含访问修饰符,字段名称,类型信息和可选的初始化表达式.
 */
public final class FieldDeclaration extends Statement {

    /** 访问标志位,编码修饰符信息(如 public,static,final 等) */
    private final int accessFlags;

    /** 字段名称 */
    private final String name;

    /** 字段的 Java 类型 */
    private final JavaType type;

    /** 字段的初始化表达式,可为 null 表示无初始化 */
    private final Expression initializer;

    /**
     * 构造一个字段声明节点.
     *
     * @param accessFlags 访问标志位
     * @param name        字段名称
     * @param type        字段类型
     * @param initializer 初始化表达式,可为 null
     */
    public FieldDeclaration(int accessFlags, String name, JavaType type, Expression initializer) {
        this.accessFlags = accessFlags;
        this.name = name;
        this.type = type;
        this.initializer = initializer;
    }

    /** @return 访问标志位 */
    public int accessFlags() {return accessFlags;}

    /** @return 字段名称 */
    public String name() {return name;}

    /** @return 字段类型 */
    public JavaType type() {return type;}

    /** @return 初始化表达式,可能为 null */
    public Expression initializer() {return initializer;}

    /** @return 是否为 static 字段(ACC_STATIC = 0x0008) */
    public boolean isStatic() {return (accessFlags & 0x0008) != 0;}

    /** @return 是否为 final 字段(ACC_FINAL = 0x0010) */
    public boolean isFinal() {return (accessFlags & 0x0010) != 0;}

    @Override
    public AstKind kind() {return AstKind.FIELD_DECL;}

    @Override
    public List<AstNode> children() {
        return initializer != null ? List.of(initializer) : List.of();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
