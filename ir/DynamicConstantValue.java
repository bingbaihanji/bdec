package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

/**
 * 动态常量(condy)IR 值——来自 {@code LDC} 加载的 {@code CONSTANT_Dynamic}.
 *
 * <p>在 IR 构建时对 {@code java.lang.invoke.ConstantBootstraps} 的标准引导方法
 * 进行识别并预解析为可渲染的 Java 表达式:</p>
 * <ul>
 *   <li>{@link Kind#NULL_CONSTANT} — {@code nullConstant} → {@code null}</li>
 *   <li>{@link Kind#CLASS_LITERAL} — {@code primitiveClass} → {@code int.class} 等</li>
 *   <li>{@link Kind#QUALIFIED_REF} — {@code enumConstant}/{@code getStaticFinal}
 *       → {@code com.pkg.Enum.CONSTANT} 形式的限定静态引用</li>
 *   <li>{@link Kind#LITERAL} — 可内联的字面量</li>
 *   <li>{@link Kind#FALLBACK} — 未知引导方法(如 {@code invoke} 的惰性求值常量),
 *       无法在源码中表达,渲染为类型默认值(null/0/false)</li>
 * </ul>
 *
 * @param type    动态常量的类型(来自 CONSTANT_Dynamic 的 nameAndType 描述符)
 * @param kind    解析出的表达式种类
 * @param owner   限定引用(QUALIFIED_REF)的拥有者全限定名,或 CLASS_LITERAL 的基本类型名
 * @param member  限定引用(QUALIFIED_REF)的成员名(枚举常量名/静态字段名)
 * @param literal 字面量值(LITERAL)
 */
public record DynamicConstantValue(JavaType type, Kind kind, String owner, String member,
                                   Object literal) implements Value {

    /** nullConstant 引导方法解析结果. */
    public static DynamicConstantValue nullConstant(JavaType type) {
        return new DynamicConstantValue(type, Kind.NULL_CONSTANT, null, null, null);
    }

    /** primitiveClass 引导方法解析结果(如 {@code int.class}). */
    public static DynamicConstantValue classLiteral(JavaType type, String primitiveName) {
        return new DynamicConstantValue(type, Kind.CLASS_LITERAL, primitiveName, null, null);
    }

    /** enumConstant / getStaticFinal 引导方法解析结果(如 {@code pkg.Enum.CONSTANT}). */
    public static DynamicConstantValue qualifiedRef(JavaType type, String owner, String member) {
        return new DynamicConstantValue(type, Kind.QUALIFIED_REF, owner, member, null);
    }

    /** 未知引导方法的类型默认值兜底. */
    public static DynamicConstantValue fallback(JavaType type) {
        return new DynamicConstantValue(type, Kind.FALLBACK, null, null, null);
    }

    /** 动态常量的表达式种类 */
    public enum Kind {
        /** null 常量 */
        NULL_CONSTANT,
        /** 基本类型类字面量(int.class 等) */
        CLASS_LITERAL,
        /** 限定静态引用(Enum.CONSTANT / Class.FIELD) */
        QUALIFIED_REF,
        /** 普通字面量 */
        LITERAL,
        /** 未知引导方法:类型默认值兜底 */
        FALLBACK
    }
}
