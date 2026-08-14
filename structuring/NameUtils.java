package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.Set;

/**
 * 名称工具集合——从 {@link StatementUtils} 中提取的函数式接口(SAM)
 * 与类型名称识别逻辑(里程碑 Phase 3).
 *
 * <p>包含 SAM 方法名判定,函数式接口类型识别,函数式接口显示名称提取.
 * 保持无状态.</p>
 */
final class NameUtils {

    /** 常见函数式接口(单一抽象方法)的方法名集合 */
    static final Set<String> SAM_METHOD_NAMES = Set.of(
            "run", "call", "get", "apply", "accept", "test",
            "compare", "compareTo", "getAsBoolean", "getAsInt",
            "getAsLong", "getAsDouble", "thenApply", "thenAccept",
            "thenRun", "thenCompose", "thenCombine", "supply",
            "applyAsInt", "applyAsLong", "applyAsDouble",
            "andThen", "compose", "negate", "or", "and");

    private NameUtils() {}

    /** 检查方法名是否为已知的 SAM(单一抽象方法)名称 */
    static boolean isSamMethodName(String name) {
        return name != null && SAM_METHOD_NAMES.contains(name);
    }

    /** 检查类型是否类似函数式接口(java.util.function.* 或类似) */
    static boolean isFunctionalInterfaceLike(JavaType type) {
        if (type == null) {
            return false;
        }
        String desc = type.descriptor();
        if (desc == null) {
            return false;
        }
        // java.util.function 包下的函数式接口
        return desc.contains("java/util/function/")
                || desc.contains("java/util/Comparator")
                || desc.contains("java/lang/Runnable")
                || desc.contains("java/util/concurrent/Callable");
    }

    /** 从函数式接口类型中提取简短显示名称 */
    static String functionalInterfaceShortName(JavaType type) {
        if (type == null) {
            return null;
        }
        String desc = type.descriptor();
        if (desc == null) {
            return null;
        }
        // 从 "Ljava/util/function/Function;" 提取 "Function"
        if (desc.startsWith("L") && desc.endsWith(";")) {
            String internal = desc.substring(1, desc.length() - 1);
            int slash = internal.lastIndexOf('/');
            return slash >= 0 ? internal.substring(slash + 1) : internal;
        }
        return desc;
    }
}
