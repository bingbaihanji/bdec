package com.bingbaihanji.bdec.ast;

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
        this.accessFlags = af;
        this.simpleName = sn;
        this.kindName = kn;
        this.superName = superName;
        this.interfaceNames = List.copyOf(interfaceNames);
        this.typeParameters = List.copyOf(typeParams);
        this.members = List.copyOf(m);
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
        this(af, sn, kn, superName, interfaceNames, List.of(), m);
    }

    /** @return 访问标志 */
    public int accessFlags() {return accessFlags;}

    /** @return 类型简单名称 */
    public String simpleName() {return simpleName;}

    /** @return 类型种类 */
    public String kindName() {return kindName;}

    /** @return 父类名称 */
    public String superName() {return superName;}

    /** @return 接口名称列表 */
    public List<String> interfaceNames() {return interfaceNames;}

    /** @return 泛型类型参数列表 */
    public List<String> typeParameters() {return typeParameters;}

    /** @return 当前类型声明是否为接口 */
    public boolean isInterface() {return (accessFlags & 0x0200) != 0;}

    @Override
    public AstKind kind() {return AstKind.TYPE_DECLARATION;}

    @Override
    public List<AstNode> children() {return members;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}
}
