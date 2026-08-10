package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * 局部变量声明节点,表示方法体内的变量声明语句.
 *
 * <p>对应 Java 语法中的 {@code Type name = init;}.
 * 当 initializer 为 null 时,仅输出 {@code Type name;} 的声明形式.
 */
public final class VariableDeclaration extends Statement {

    /** 变量类型 */
    private final JavaType type;

    /** 变量名称 */
    private final String name;

    /** 变量初始化表达式,可为 null */
    private final Expression initializer;

    /**
     * 构造一个局部变量声明.
     *
     * @param type        变量类型
     * @param name        变量名称
     * @param initializer 初始化表达式,可为 null
     */
    public VariableDeclaration(JavaType type, String name, Expression initializer) {
        this.type = type;
        this.name = name;
        this.initializer = initializer;
    }

    /** @return 变量类型 */
    public JavaType type() {return type;}

    /** @return 变量名称 */
    public String name() {return name;}

    /** @return 初始化表达式,可为 null */
    public Expression initializer() {return initializer;}

    @Override
    public AstKind kind() {return AstKind.VARIABLE_DECL;}

    @Override
    public List<AstNode> children() {
        List<AstNode> c = new ArrayList<>();
        if (initializer != null) {
            c.add(initializer);
        }
        return c;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
