package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.bytecode.model.AnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.TypePathElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 注解渲染工具——把 {@link AnnotationEntry} 渲染为 Java 源码片段.
 *
 * <p>从 {@link AstBuilder} 中提取,供需要渲染注解的非 AST 层
 * (如 {@code IrBuilder} 为局部变量附加类型注解,
 * {@code BlockReducer} 构建声明)复用.</p>
 */
public final class AnnotationRenderer {

    private AnnotationRenderer() {}

    /** 渲染注解为源码行,如 "@Retention(RetentionPolicy.RUNTIME)".
     *  @param simpleName 内部名 → 简单名解析器(内部类友好名解析) */
    public static String render(AnnotationEntry ann, Function<String, String> simpleName) {
        String typeName = simpleName.apply(ann.typeName());
        StringBuilder sb = new StringBuilder("@").append(typeName);
        if (!ann.pairs().isEmpty()) {
            sb.append('(');
            for (int i = 0; i < ann.pairs().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                var pair = ann.pairs().get(i);
                // 单元素 value 注解省略 "value = "(Java 语法糖)
                if (ann.pairs().size() == 1 && "value".equals(pair.name())) {
                    sb.append(renderValue(pair.value(), simpleName));
                } else {
                    sb.append(pair.name()).append(" = ")
                            .append(renderValue(pair.value(), simpleName));
                }
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * 将类型注解按类型路径分组为渲染行映射.
     *
     * <p>{@code BlockReducer} 为局部变量(JSR-308 0x40)与指令偏移目标
     * (instanceof/new/cast)附加类型注解时,按 {@link TypePathElement}
     * 分组后由发射器逐行渲染.注解类型名取简单名(与声明点同包/已导入
     * 假设一致).</p>
     */
    public static Map<List<TypePathElement>, List<String>> groupByTypePath(
            List<TypeAnnotationEntry> entries) {
        Map<List<TypePathElement>, List<String>> map = new LinkedHashMap<>();
        for (TypeAnnotationEntry ta : entries) {
            String rendered = render(ta.annotation(), AnnotationRenderer::simpleName);
            map.computeIfAbsent(ta.typePath(), k -> new ArrayList<>()).add(rendered);
        }
        return map;
    }

    /** 内部名 → 简单名(取最后一个 '/' 之后的段). */
    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /** 渲染注解元素值为 Java 源码片段 */
    public static String renderValue(Object v, Function<String, String> simpleName) {
        return switch (v) {
            case String s -> "\"" + s + "\"";
            case Integer i -> String.valueOf(i);
            case Boolean b -> String.valueOf(b);
            case Character c -> "'" + c + "'";
            case Long l -> String.valueOf(l) + "L";
            case Float f -> String.valueOf(f) + "f";
            case Double d -> String.valueOf(d);
            case AnnotationEntry.EnumValue ev -> simpleName.apply(ev.typeName()) + "." + ev.constName();
            case AnnotationEntry.ClassValue cv -> simpleName.apply(cv.internalName()) + ".class";
            case AnnotationEntry nested -> render(nested, simpleName);
            case java.util.List<?> list -> {
                StringBuilder sb = new StringBuilder("{");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(renderValue(list.get(i), simpleName));
                }
                yield sb.append("}").toString();
            }
            case null -> "null";
            default -> String.valueOf(v);
        };
    }
}
