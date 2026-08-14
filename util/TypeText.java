package com.bingbaihanji.bdec.util;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 重写器文本拼接路径的类型渲染与 import 合并入口(里程碑 Phase 2.1).
 *
 * <p>类型名渲染逻辑已收敛到 {@link TypeNameRenderer}(单一事实源);本类保留
 * 面向重写器的便捷门面:{@link #render} 委托 {@code TypeNameRenderer.render}
 * (收集模式),{@link #mergeImports} 将收集到的 import 合并进编译单元列表。</p>
 */
public final class TypeText {

    private TypeText() {
    }

    /**
     * 渲染一个 JavaType 树为 import 感知的源码类型文本.
     *
     * @param type             要渲染的类型
     * @param currentPackage   当前编译单元的包名(默认包为 "")
     * @param innerClassNames  内部类友好名称映射(可为空)
     * @param collectedImports 需收集的 import 全限定名集合(可为 null,表示不收集)
     * @return 渲染后的类型文本
     */
    public static String render(JavaType type, String currentPackage,
                                Map<String, String> innerClassNames,
                                Set<String> collectedImports) {
        return TypeNameRenderer.render(type, currentPackage, innerClassNames, collectedImports);
    }

    /**
     * 将收集到的 import 合并进既有 import 列表(去重并保持排序,
     * 与 AstBuilder 输出前的排序一致).
     *
     * @param existing  既有 import 列表
     * @param collected 新收集的全限定名集合(可为 null 或空)
     * @return 合并后的不可变列表
     */
    public static List<String> mergeImports(List<String> existing, Set<String> collected) {
        if (collected == null || collected.isEmpty()) {
            return existing;
        }
        Set<String> merged = new TreeSet<>(existing);
        merged.addAll(collected);
        return List.copyOf(merged);
    }
}
