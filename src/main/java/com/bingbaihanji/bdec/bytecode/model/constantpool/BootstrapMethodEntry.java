package com.bingbaihanji.bdec.bytecode.model.constantpool;

import java.util.List;

/**
 * 引导方法条目.
 *
 * <p>表示类文件中 {@code BootstrapMethods} 属性的一条记录.
 * 引导方法用于支持 {@code invokedynamic} 指令(Java 7+ 引入),
 * 实现 lambda 表达式,字符串拼接,方法引用等动态调用特性.
 *
 * @param methodRef 常量池索引,指向一个 {@code CONSTANT_MethodHandle_info} 条目
 * @param arguments 静态参数列表,每个元素为常量池索引,传递给引导方法
 */
public record BootstrapMethodEntry(
        int methodRef,
        List<Integer> arguments
) {}
