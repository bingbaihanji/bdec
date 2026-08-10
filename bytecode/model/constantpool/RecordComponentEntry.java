package com.bingbaihanji.bdec.bytecode.model.constantpool;

/**
 * 记录组件条目.
 *
 * <p>表示类文件中 {@code Record} 属性(Java 16+ 引入)的一个组件.
 * 每个组件对应 {@code record} 类声明中的一个字段,包含组件名称和类型描述符.
 *
 * @param name       组件名称(从常量池 UTF-8 条目解析得到)
 * @param descriptor 组件类型描述符(如 {@code "Ljava/lang/String;"})
 */
public record RecordComponentEntry(
        String name,
        String descriptor
) {}
