package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * 方法声明节点,表示类或接口中的方法定义.
 *
 * <p>包含方法的访问修饰符,名称,返回类型,参数列表,类型参数以及方法体.
 * body 为 null 时表示抽象方法或 native 方法(无方法体).
 */
public final class MethodDeclaration extends Statement {

    /** 访问标志位,编码修饰符信息 */
    private final int accessFlags;

    /** 方法名称 */
    private final String name;

    /** 方法返回类型 */
    private final JavaType returnType;

    /** 方法参数名称数组 */
    private final String[] parameterNames;

    /** 方法参数类型数组,与 parameterNames 一一对应 */
    private final JavaType[] parameterTypes;

    /** 类型参数列表(泛型参数) */
    private final List<String> typeParameters;

    /** 方法体语句,可为 null */
    private final Statement body;

    /**
     * 构造一个方法声明节点(无类型参数).
     *
     * @param accessFlags 访问标志位
     * @param name        方法名称
     * @param returnType  返回类型
     * @param paramNames  参数名称数组
     * @param paramTypes  参数类型数组
     * @param body        方法体语句,可为 null
     */
    public MethodDeclaration(int accessFlags, String name, JavaType returnType,
                             String[] paramNames, JavaType[] paramTypes, Statement body) {
        this(accessFlags, name, returnType, paramNames, paramTypes, List.of(), body);
    }

    /**
     * 构造一个方法声明节点(含类型参数).
     *
     * @param accessFlags    访问标志位
     * @param name           方法名称
     * @param returnType     返回类型
     * @param paramNames     参数名称数组
     * @param paramTypes     参数类型数组
     * @param typeParameters 类型参数列表
     * @param body           方法体语句,可为 null
     */
    public MethodDeclaration(int accessFlags, String name, JavaType returnType,
                             String[] paramNames, JavaType[] paramTypes,
                             List<String> typeParameters, Statement body) {
        this.accessFlags = accessFlags;
        this.name = name;
        this.returnType = returnType;
        this.parameterNames = paramNames;
        this.parameterTypes = paramTypes;
        this.typeParameters = List.copyOf(typeParameters);
        this.body = body;
    }

    /** @return 访问标志位 */
    public int accessFlags() {return accessFlags;}

    /** @return 方法名称 */
    public String name() {return name;}

    /** @return 返回类型 */
    public JavaType returnType() {return returnType;}

    /** @return 参数名称数组 */
    public String[] parameterNames() {return parameterNames;}

    /** @return 参数类型数组 */
    public JavaType[] parameterTypes() {return parameterTypes;}

    /** @return 类型参数列表(不可变) */
    public List<String> typeParameters() {return typeParameters;}

    /** @return 方法体语句,可为 null */
    public Statement body() {return body;}

    /** @return 是否为 static 方法 */
    public boolean isStatic() {return (accessFlags & 0x0008) != 0;}

    @Override
    public AstKind kind() {return AstKind.METHOD_DECL;}

    @Override
    public List<AstNode> children() {return body != null ? List.of(body) : List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
