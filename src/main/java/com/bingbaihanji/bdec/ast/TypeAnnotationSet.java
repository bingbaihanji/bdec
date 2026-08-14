package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.bytecode.model.TypePathElement;

import java.util.List;
import java.util.Map;

/**
 * 方法签名上的 JSR-308 类型注解集合(已渲染为源码行).
 *
 * <p>键为类型路径(注解在类型树中的位置,如 {@code [TYPE_ARGUMENT(0)]}
 * 表示泛型参数 0),值为该位置上的渲染后注解行列表
 * (如 {@code ["@NonNull"]} 或 {@code ["@Ann(\"x\")"]}).</p>
 *
 * @param onType       返回类型上的注解(按类型路径分组)
 * @param onParameters 每个形式参数类型上的注解(与参数列表对齐;无注解时为不可变空表)
 * @param onThrows     每个 throws 子句类型上的注解(与 throws 列表对齐)
 */
public record TypeAnnotationSet(
        Map<List<TypePathElement>, List<String>> onType,
        List<Map<List<TypePathElement>, List<String>>> onParameters,
        List<Map<List<TypePathElement>, List<String>>> onThrows
) {

    /** 无类型注解的空集合. */
    public static final TypeAnnotationSet NONE =
            new TypeAnnotationSet(Map.of(), List.of(), List.of());
}
