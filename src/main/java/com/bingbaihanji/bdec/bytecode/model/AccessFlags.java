package com.bingbaihanji.bdec.bytecode.model;

/**
 * JVM 类文件访问标志常量表(JVMS §4.1 / §4.5 / §4.6 / §4.7).
 *
 * <p>集中定义 {@code access_flags} 的各位掩码,替代散落全库的十六进制魔法数字.
 * 注意:若干位在类/字段/方法/模块上下文中有不同含义,故提供语义化别名(值相同),
 * 调用方按上下文选取对应常量:</p>
 * <ul>
 *   <li>{@code 0x0020}:类 {@link #ACC_SUPER} / 方法 {@link #ACC_SYNCHRONIZED} / 模块 {@link #ACC_OPEN}</li>
 *   <li>{@code 0x0040}:字段 {@link #ACC_VOLATILE} / 方法 {@link #ACC_BRIDGE}</li>
 *   <li>{@code 0x0080}:字段 {@link #ACC_TRANSIENT} / 方法 {@link #ACC_VARARGS}</li>
 *   <li>{@code 0x1000}:类/字段/方法 {@link #ACC_SYNTHETIC} / 类(Java 17) {@link #ACC_SEALED}</li>
 *   <li>{@code 0x8000}:类 {@link #ACC_MODULE} / 方法参数 {@link #ACC_MANDATED}</li>
 * </ul>
 */
public final class AccessFlags {

    /** 类/字段/方法:public */
    public static final int ACC_PUBLIC = 0x0001;

    /** 类/字段/方法:private */
    public static final int ACC_PRIVATE = 0x0002;

    /** 类/字段/方法:protected */
    public static final int ACC_PROTECTED = 0x0004;

    /** 类/字段/方法:static */
    public static final int ACC_STATIC = 0x0008;

    /** 类/字段/方法:final */
    public static final int ACC_FINAL = 0x0010;

    /** 类:super 关键字(现代 class 文件恒设置,不应作为修饰符输出) */
    public static final int ACC_SUPER = 0x0020;

    /** 方法:synchronized */
    public static final int ACC_SYNCHRONIZED = 0x0020;

    /** 模块:open module */
    public static final int ACC_OPEN = 0x0020;

    /** 模块 requires:transitive */
    public static final int ACC_TRANSITIVE = 0x0020;

    /** 字段:volatile */
    public static final int ACC_VOLATILE = 0x0040;

    /** 方法:bridge(编译器合成) */
    public static final int ACC_BRIDGE = 0x0040;

    /** 模块 requires:static phase */
    public static final int ACC_STATIC_PHASE = 0x0040;

    /** 字段:transient */
    public static final int ACC_TRANSIENT = 0x0080;

    /** 方法:可变参数 varargs */
    public static final int ACC_VARARGS = 0x0080;

    /** 方法:native */
    public static final int ACC_NATIVE = 0x0100;

    /** 类:interface */
    public static final int ACC_INTERFACE = 0x0200;

    /** 类/方法:abstract */
    public static final int ACC_ABSTRACT = 0x0400;

    /** 方法:strictfp */
    public static final int ACC_STRICT = 0x0800;

    /** 类/字段/方法:编译器合成(synthetic) */
    public static final int ACC_SYNTHETIC = 0x1000;

    /** 类(Java 17+):sealed,与 {@link #ACC_SYNTHETIC} 同值不同义 */
    public static final int ACC_SEALED = 0x1000;

    /** 类:annotation 类型 */
    public static final int ACC_ANNOTATION = 0x2000;

    /** 类:enum */
    public static final int ACC_ENUM = 0x4000;

    /** 类:module-info */
    public static final int ACC_MODULE = 0x8000;

    /** 方法参数/模块:隐式声明(mandated) */
    public static final int ACC_MANDATED = 0x8000;

    private AccessFlags() {
    }
}
