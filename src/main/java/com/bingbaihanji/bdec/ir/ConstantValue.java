package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

/**
 * 常量值记录.
 * <p>
 * 表示IR中的字面常量值,如整数,浮点数,字符串等.
 * 实现{@link Value}接口,可作为IR指令的操作数.
 * 提供了空值检测和零值检测等便捷方法.
 * </p>
 *
 * @param value 常量的Java对象值,{@code null} 表示null常量
 * @param type  常量的Java类型
 */
public record ConstantValue(Object value, JavaType type) implements Value {

    /** 空值常量实例,类型为 java/lang/Object */
    public static final ConstantValue NULL = new ConstantValue(null,
            JavaType.classType("java/lang/Object"));

    /**
     * 判断当前常量是否为空值.
     *
     * @return 如果值为 {@code null} 则返回 {@code true}
     */
    public boolean isNull() {return value == null;}

    /**
     * 判断当前常量是否为零值.
     * 支持 Integer,Long,Float,Double,Boolean 类型的零值判断.
     *
     * @return 如果为零值则返回 {@code true}
     */
    public boolean isZero() {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case Integer i -> i == 0;
            case Long l -> l == 0L;
            case Float f -> f == 0.0f;
            case Double d -> d == 0.0;
            case Boolean b -> !b;
            default -> false;
        };
    }
}
