package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.bytecode.model.AccessFlags;

import java.util.List;

/**
 * 类型声明节点.
 * <p>
 * 表示一个Java类型声明(类,接口,枚举或注解类型),包含访问标志,
 * 类型名称,类型种类,父类,实现的接口列表,泛型类型参数以及成员列表
 * (字段,方法,内部类等).
 * </p>
 */
public final class TypeDeclaration implements AstNode {

    /** 访问标志(public/private/protected/static/final等JVM标志位) */
    private final int accessFlags;

    /** 类型简单名称(不含包名) */
    private final String simpleName;

    /** 类型种类("class","interface","enum","@interface") */
    private final String kindName;

    /** 父类名称,若为java.lang.Object则为null */
    private final String superName;

    /** 实现的接口名称列表 */
    private final List<String> interfaceNames;

    /** 泛型类型参数列表 */
    private final List<String> typeParameters;

    /** 类型成员列表(字段,方法,内部类等AST节点) */
    private final List<AstNode> members;

    /** 类级注解(已渲染的源码行,如 "@Retention(RetentionPolicy.RUNTIME)") */
    private final List<String> annotations;

    /** 父类型注解(已渲染的源码行,如 "extends @A Parent" 中的 "@A";空路径) */
    private final List<String> superAnnotations;

    /** 接口注解(已渲染的源码行,如 "implements @A I" 中的 "@A";
     *  与 interfaceNames 按索引对齐,无注解接口用空串占位) */
    private final List<String> interfaceAnnotations;

    /** 密封类 permits 子句中的允许子类简称列表(如 ["Circle","Square"]);非密封类为空 */
    private final List<String> permitsNames;

    /**
     * record 组件列表(如 {@code ["L left", "R right"]}).
     *
     * <p>与 {@link #typeParameters}(泛型参数 {@code <L, R>}) 分离:record 声明
     * 需同时输出 {@code record Pair<L, R>(L left, R right)}——类型参数与组件
     * 是两个独立槽位,此前组件占用 typeParameters 导致泛型参数丢失.</p>
     */
    private final List<String> recordComponents;

    /**
     * 构造一个类型声明节点(含泛型类型参数).
     *
     * @param af              访问标志
     * @param sn              简单名称
     * @param kn              类型种类
     * @param superName       父类名称
     * @param interfaceNames  接口名称列表
     * @param typeParams      泛型类型参数列表
     * @param m               成员列表
     */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<String> typeParams,
                           List<AstNode> m) {
        this(af, sn, kn, superName, interfaceNames, typeParams, m,
                List.of(), List.of(), List.of());
    }

    /**
     * 构造一个类型声明节点(含类级注解).
     *
     * @param af              访问标志
     * @param sn              简单名称
     * @param kn              类型种类
     * @param superName       父类名称
     * @param interfaceNames  接口名称列表
     * @param typeParams      泛型类型参数列表
     * @param m               成员列表
     * @param annotations     类级注解(已渲染的源码行)
     */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<String> typeParams,
                           List<AstNode> m, List<String> annotations) {
        this(af, sn, kn, superName, interfaceNames, typeParams, m, annotations,
                List.of(), List.of());
    }

    /**
     * 构造一个类型声明节点(含类级注解与父类型注解).
     *
     * @param superAnnotations 父类型注解(已渲染的源码行,空路径)
     */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<String> typeParams,
                           List<AstNode> m, List<String> annotations,
                           List<String> superAnnotations) {
        this(af, sn, kn, superName, interfaceNames, typeParams, m, annotations,
                superAnnotations, List.of());
    }

    /**
     * 构造一个类型声明节点(含类级注解,父类型注解与接口注解).
     *
     * @param interfaceAnnotations 接口注解(已渲染的源码行,
     *                             与 interfaceNames 按索引对齐,无注解为空串)
     */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<String> typeParams,
                           List<AstNode> m, List<String> annotations,
                           List<String> superAnnotations,
                           List<String> interfaceAnnotations) {
        this(af, sn, kn, superName, interfaceNames, typeParams, m, annotations,
                superAnnotations, interfaceAnnotations, List.of());
    }

    /**
     * 构造一个类型声明节点(含类级注解,父类型注解,接口注解与 sealed permits 子类).
     *
     * @param permitsNames 密封类 permits 子句中的允许子类简称列表;非密封类传空列表
     */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<String> typeParams,
                           List<AstNode> m, List<String> annotations,
                           List<String> superAnnotations,
                           List<String> interfaceAnnotations,
                           List<String> permitsNames) {
        this(af, sn, kn, superName, interfaceNames, typeParams, m, annotations,
                superAnnotations, interfaceAnnotations, permitsNames, List.of());
    }

    /**
     * 完整构造器(含 record 组件).record 类由 RecordRewriter 调用:
     * typeParams 为泛型参数({@code <L, R>}),recordComponents 为组件
     * ({@code (L left, R right)});非 record 传空列表.
     */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<String> typeParams,
                           List<AstNode> m, List<String> annotations,
                           List<String> superAnnotations,
                           List<String> interfaceAnnotations,
                           List<String> permitsNames,
                           List<String> recordComponents) {
        this.accessFlags = af;
        this.simpleName = sn;
        this.kindName = kn;
        this.superName = superName;
        this.interfaceNames = List.copyOf(interfaceNames);
        this.typeParameters = List.copyOf(typeParams);
        this.members = List.copyOf(m);
        this.annotations = List.copyOf(annotations);
        this.superAnnotations = List.copyOf(superAnnotations);
        this.interfaceAnnotations = List.copyOf(interfaceAnnotations);
        this.permitsNames = List.copyOf(permitsNames);
        this.recordComponents = List.copyOf(recordComponents);
    }

    /**
     * 构造一个类型声明节点(不含泛型类型参数,向后兼容).
     *
     * @param af              访问标志
     * @param sn              简单名称
     * @param kn              类型种类
     * @param superName       父类名称
     * @param interfaceNames  接口名称列表
     * @param m               成员列表
     */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<AstNode> m) {
        this(af, sn, kn, superName, interfaceNames, List.of(), m, List.of(), List.of());
    }

    /** @return 访问标志 */
    public int accessFlags() {return accessFlags;}

    /** @return 类型简单名称 */
    public String simpleName() {return simpleName;}

    /** @return 类级注解(已渲染的源码行) */
    public List<String> annotations() {return annotations;}

    /** @return 父类型注解(已渲染的源码行) */
    public List<String> superAnnotations() {return superAnnotations;}

    /** @return 接口注解(已渲染的源码行,与 interfaceNames 按索引对齐,无注解为空串) */
    public List<String> interfaceAnnotations() {return interfaceAnnotations;}

    /** @return 密封类 permits 子句中的允许子类简称列表;非密封类为空 */
    public List<String> permitsNames() {return permitsNames;}

    /** @return 类型种类 */
    public String kindName() {return kindName;}

    /** @return 父类名称 */
    public String superName() {return superName;}

    /** @return 接口名称列表 */
    public List<String> interfaceNames() {return interfaceNames;}

    /** @return 泛型类型参数列表 */
    public List<String> typeParameters() {return typeParameters;}

    /** @return record 组件列表(如 {@code ["L left", "R right"]});非 record 为空 */
    public List<String> recordComponents() {return recordComponents;}

    /** @return 当前类型声明是否为接口 */
    public boolean isInterface() {return (accessFlags & AccessFlags.ACC_INTERFACE) != 0;}

    @Override
    public AstKind kind() {return AstKind.TYPE_DECLARATION;}

    @Override
    public List<AstNode> children() {return members;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}
}
