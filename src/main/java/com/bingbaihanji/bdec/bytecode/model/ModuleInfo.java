package com.bingbaihanji.bdec.bytecode.model;

import java.util.List;

/**
 * 模块信息模型——来自 module-info.class 的 Module 属性(JVMS 4.7.25).
 *
 * @param name     模块名称(如 {@code "com.example.app"})
 * @param flags    模块标志({@code ACC_OPEN} 0x0020 表示 open module,
 *                 {@code ACC_SYNTHETIC} 0x1000,{@code ACC_MANDATED} 0x8000)
 * @param version  模块版本字符串,可为 {@code null}
 * @param requires requires 子句列表
 * @param exports  exports 子句列表
 * @param opens    opens 子句列表
 * @param uses     uses 子句的服务接口内部名称列表
 * @param provides provides 子句列表
 */
public record ModuleInfo(
        String name,
        int flags,
        String version,
        List<RequiresEntry> requires,
        List<ExportsEntry> exports,
        List<OpensEntry> opens,
        List<String> uses,
        List<ProvidesEntry> provides
) {

    /** open module 标志 */
    public static final int ACC_OPEN = 0x0020;

    /** requires 条目:依赖模块及其修饰符与版本. */
    public record RequiresEntry(String module, int flags, String version) {
        /** requires transitive 标志 */
        public static final int ACC_TRANSITIVE = 0x0020;
        /** requires static 标志 */
        public static final int ACC_STATIC_PHASE = 0x0040;
    }

    /** exports 条目:导出的包及其修饰符与目标模块(空列表表示无限制导出). */
    public record ExportsEntry(String packageName, int flags, List<String> toModules) {}

    /** opens 条目:开放的包及其修饰符与目标模块. */
    public record OpensEntry(String packageName, int flags, List<String> toModules) {}

    /** provides 条目:服务接口及其实现类列表. */
    public record ProvidesEntry(String service, List<String> withImplementations) {}
}
