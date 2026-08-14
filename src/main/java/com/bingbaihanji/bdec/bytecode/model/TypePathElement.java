package com.bingbaihanji.bdec.bytecode.model;

/**
 * 类型注解路径中的一个元素(JVMS 4.7.20.2).
 *
 * <p>type_path 描述了注解所应用的类型在完整类型结构中的位置:
 * 从最外层类型出发,每个元素指示如何深入一层.</p>
 *
 * @param kind          路径种类:{@link #KIND_ARRAY},{@link #KIND_INNER_TYPE},
 *                      {@link #KIND_WILDCARD_BOUND} 或 {@link #KIND_TYPE_ARGUMENT}
 * @param argumentIndex 参数索引(含义随 kind 而定)
 */
public record TypePathElement(int kind, int argumentIndex) {

    /** 进入数组元素类型(argumentIndex 为维度) */
    public static final int KIND_ARRAY = 0;
    /** 进入嵌套类型的组成部分(argumentIndex 为组成部分下标,按 $ 拆分) */
    public static final int KIND_INNER_TYPE = 1;
    /** 进入通配符边界类型(argumentIndex 为 0) */
    public static final int KIND_WILDCARD_BOUND = 2;
    /** 进入类型参数(argumentIndex 为类型参数下标) */
    public static final int KIND_TYPE_ARGUMENT = 3;
}
