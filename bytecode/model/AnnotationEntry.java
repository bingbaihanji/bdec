package com.bingbaihanji.bdec.bytecode.model;

import java.util.List;

/**
 * 注解实例模型——来自 RuntimeVisibleAnnotations / RuntimeInvisibleAnnotations 属性.
 *
 * @param typeName 注解类型内部名(如 com/bytecode/test/AnnotationDemo)
 * @param pairs    元素名-值对列表(有序)
 */
public record AnnotationEntry(String typeName, List<ElementPair> pairs) {

    /** 注解元素对:元素名 → element_value(类型化值) */
    public record ElementPair(String name, Object value) {}

    /**
     * 枚举常量值({@code RetentionPolicy.RUNTIME} 形式).
     *
     * @param typeName   枚举类型内部名
     * @param constName  常量名
     */
    public record EnumValue(String typeName, String constName) {}

    /**
     * 类字面量值({@code String.class} 形式).
     *
     * @param internalName 类内部名(java/lang/String)
     */
    public record ClassValue(String internalName) {}
}
