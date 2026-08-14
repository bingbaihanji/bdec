package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.TypeAnnotationSet;
import com.bingbaihanji.bdec.bytecode.model.AccessFlags;
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

    /** 方法声明的 throws 异常类型列表(简单名) */
    private final List<String> throwsTypes;

    /** 方法体语句,可为 null */
    private final Statement body;

    /** 注解方法元素的默认值(已渲染的源码,普通方法为 null) */
    private final String annotationDefault;

    /** 方法上的注解(已渲染的源码行),无则为空列表 */
    private final List<String> annotations;

    /**
     * 参数级注解(已渲染的源码行),与 parameterNames 一一对齐;
     * 无参数注解时为 null,单参数无注解时对应元素为 null.
     */
    private final String[] parameterAnnotations;

    /** 方法签名上的 JSR-308 类型注解(返回/参数/throws 类型,按类型路径分组) */
    private final TypeAnnotationSet typeAnnotations;

    /**
     * 构造一个方法声明节点(无类型参数,无 throws).
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
        this(accessFlags, name, returnType, paramNames, paramTypes,
                List.of(), List.of(), null, List.of(), null, TypeAnnotationSet.NONE, body);
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
        this(accessFlags, name, returnType, paramNames, paramTypes,
                typeParameters, List.of(), null, List.of(), null, TypeAnnotationSet.NONE, body);
    }

    /**
     * 构造一个方法声明节点(含类型参数和 throws 子句).
     *
     * @param accessFlags    访问标志位
     * @param name           方法名称
     * @param returnType     返回类型
     * @param paramNames     参数名称数组
     * @param paramTypes     参数类型数组
     * @param typeParameters 类型参数列表
     * @param throwsTypes    throws 异常类型列表(简单名)
     * @param body           方法体语句,可为 null
     */
    public MethodDeclaration(int accessFlags, String name, JavaType returnType,
                             String[] paramNames, JavaType[] paramTypes,
                             List<String> typeParameters, List<String> throwsTypes,
                             Statement body) {
        this(accessFlags, name, returnType, paramNames, paramTypes,
                typeParameters, throwsTypes, null, List.of(), null, TypeAnnotationSet.NONE, body);
    }

    /**
     * 构造一个方法声明节点(含注解默认值,注解与参数注解).
     *
     * @param annotationDefault     注解方法元素的默认值(已渲染的源码)
     * @param annotations           方法上的注解(已渲染的源码行)
     * @param parameterAnnotations  参数级注解(已渲染的源码行),
     *                              与 paramNames 一一对齐,可为 null
     */
    public MethodDeclaration(int accessFlags, String name, JavaType returnType,
                             String[] paramNames, JavaType[] paramTypes,
                             List<String> typeParameters, List<String> throwsTypes,
                             String annotationDefault, List<String> annotations,
                             String[] parameterAnnotations, Statement body) {
        this(accessFlags, name, returnType, paramNames, paramTypes,
                typeParameters, throwsTypes, annotationDefault, annotations,
                parameterAnnotations, TypeAnnotationSet.NONE, body);
    }

    /**
     * 构造一个方法声明节点(含注解默认值,注解,参数注解与类型注解).
     *
     * @param typeAnnotations  方法签名上的类型注解(返回/参数/throws 类型)
     */
    public MethodDeclaration(int accessFlags, String name, JavaType returnType,
                             String[] paramNames, JavaType[] paramTypes,
                             List<String> typeParameters, List<String> throwsTypes,
                             String annotationDefault, List<String> annotations,
                             String[] parameterAnnotations, TypeAnnotationSet typeAnnotations,
                             Statement body) {
        this.accessFlags = accessFlags;
        this.name = name;
        this.returnType = returnType;
        this.parameterNames = paramNames;
        this.parameterTypes = paramTypes;
        this.typeParameters = List.copyOf(typeParameters);
        this.throwsTypes = List.copyOf(throwsTypes);
        this.annotationDefault = annotationDefault;
        this.annotations = List.copyOf(annotations);
        this.parameterAnnotations = parameterAnnotations;
        this.typeAnnotations = typeAnnotations != null ? typeAnnotations : TypeAnnotationSet.NONE;
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

    /** @return throws 异常类型列表(不可变) */
    public List<String> throwsTypes() {return throwsTypes;}

    /** @return 方法体语句,可为 null */
    public Statement body() {return body;}

    /** @return 注解方法元素的默认值(已渲染的源码,普通方法为 null) */
    public String annotationDefault() {return annotationDefault;}

    /** @return 方法上的注解(已渲染的源码行) */
    public List<String> annotations() {return annotations;}

    /** @return 参数级注解(已渲染的源码行),与 parameterNames 一一对齐,可为 null */
    public String[] parameterAnnotations() {return parameterAnnotations;}

    /** @return 方法签名上的 JSR-308 类型注解 */
    public TypeAnnotationSet typeAnnotations() {return typeAnnotations;}

    /** @return 是否为 static 方法 */
    public boolean isStatic() {return (accessFlags & AccessFlags.ACC_STATIC) != 0;}

    @Override
    public AstKind kind() {return AstKind.METHOD_DECL;}

    @Override
    public List<AstNode> children() {return body != null ? List.of(body) : List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
