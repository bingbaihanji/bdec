package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * 字段模型.
 *
 * <p>封装 Java 类文件中一个字段({@code field_info})的核心信息,
 * 包括访问标志,名称,类型,常量值(来自 {@code ConstantValue} 属性)和泛型签名.
 *
 * @param accessFlags   字段访问标志({@code ACC_PUBLIC},{@code ACC_STATIC},{@code ACC_FINAL} 等)
 * @param name          字段名称
 * @param type          字段的 Java 类型
 * @param constantValue 常量值,若字段非编译期常量则为 {@code null}
 * @param signature     字段的泛型签名属性,若无则为空字符串
 * @param annotations     字段上的注解(RuntimeVisible/InvisibleAnnotations),无则为空列表
 * @param typeAnnotations 字段类型上的 JSR-308 类型注解(RuntimeVisibleTypeAnnotations),无则为空列表
 */
public record FieldModel(
        int accessFlags,
        String name,
        JavaType type,
        Object constantValue,
        String signature,
        List<AnnotationEntry> annotations,
        List<TypeAnnotationEntry> typeAnnotations
) {
}
