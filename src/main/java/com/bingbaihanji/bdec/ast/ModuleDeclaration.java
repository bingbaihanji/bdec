package com.bingbaihanji.bdec.ast;

import java.util.List;

/**
 * 模块声明节点,表示 module-info.java 中的模块声明
 * (来自 class 文件的 Module 属性,JVMS 4.7.25).
 *
 * <p>例如:</p>
 * <pre>
 * module com.example.app {
 *     requires transitive java.sql;
 *     exports com.example.api to com.example.client;
 *     uses com.example.spi.Service;
 *     provides com.example.spi.Service with com.example.impl.ServiceImpl;
 * }
 * </pre>
 */
public final class ModuleDeclaration implements AstNode {

    /** 模块名称 */
    private final String name;

    /** 是否为 open module(ACC_OPEN = 0x0020) */
    private final boolean isOpen;

    /** 模块版本,可为 null(源码形如 {@code module m @ 1.0 {}) */
    private final String version;

    /** requires 子句列表 */
    private final List<RequiresClause> requires;

    /** exports 子句列表 */
    private final List<ExportsClause> exports;

    /** opens 子句列表 */
    private final List<OpensClause> opens;

    /** uses 子句的服务接口全限定名列表 */
    private final List<String> uses;

    /** provides 子句列表 */
    private final List<ProvidesClause> provides;

    /**
     * 构造一个模块声明节点.
     */
    public ModuleDeclaration(String name, boolean isOpen, String version,
                             List<RequiresClause> requires, List<ExportsClause> exports,
                             List<OpensClause> opens, List<String> uses,
                             List<ProvidesClause> provides) {
        this.name = name;
        this.isOpen = isOpen;
        this.version = version;
        this.requires = List.copyOf(requires);
        this.exports = List.copyOf(exports);
        this.opens = List.copyOf(opens);
        this.uses = List.copyOf(uses);
        this.provides = List.copyOf(provides);
    }

    /** @return 模块名称 */
    public String name() {return name;}

    /** @return 是否为 open module */
    public boolean isOpen() {return isOpen;}

    /** @return 模块版本,可为 null */
    public String version() {return version;}

    /** @return requires 子句列表(不可变) */
    public List<RequiresClause> requires() {return requires;}

    /** @return exports 子句列表(不可变) */
    public List<ExportsClause> exports() {return exports;}

    /** @return opens 子句列表(不可变) */
    public List<OpensClause> opens() {return opens;}

    /** @return uses 子句的服务接口列表(不可变) */
    public List<String> uses() {return uses;}

    /** @return provides 子句列表(不可变) */
    public List<ProvidesClause> provides() {return provides;}

    @Override
    public AstKind kind() {return AstKind.MODULE_DECL;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}

    /** requires 子句:{@code requires [transitive] [static] module;} */
    public record RequiresClause(String module, boolean transitive, boolean staticPhase) {}

    /** exports 子句:{@code exports pkg [to m1, m2];} */
    public record ExportsClause(String packageName, List<String> toModules) {}

    /** opens 子句:{@code opens pkg [to m1, m2];} */
    public record OpensClause(String packageName, List<String> toModules) {}

    /** provides 子句:{@code provides service with impl1, impl2;} */
    public record ProvidesClause(String service, List<String> withImplementations) {}
}
