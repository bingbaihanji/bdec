package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * Set 工具类,提供判空,集合运算(交/并/差),元素拼接,合并等常用操作
 *
 * @author bingbaihanji
 * @date 2026-06-12
 */
public final class SetUtils {

    private SetUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    // 判空

    /**
     * 判断 Set 是否为 null 或无元素
     */
    public static boolean isNullOrEmpty(Set<?> set) {
        return set == null || set.isEmpty();
    }

    /**
     * 判断 Set 是否非 null 且有元素
     */
    public static boolean isNotEmpty(Set<?> set) {
        return set != null && !set.isEmpty();
    }

    /**
     * 如果 set 为 null 或空,则返回 defaultSet,否则返回原 set
     */
    public static <T> Set<T> defaultIfEmpty(Set<T> set, Set<T> defaultSet) {
        return isNotEmpty(set) ? set : defaultSet;
    }

    /**
     * 返回不可变的空 Set
     */
    public static <T> Set<T> emptySet() {
        return Collections.emptySet();
    }

    // 创建

    /**
     * 快速创建包含指定元素的 HashSet
     */
    @SafeVarargs
    public static <T> Set<T> of(T... items) {
        Set<T> set = HashSet.newHashSet(items.length);
        Collections.addAll(set, items);
        return set;
    }

    /**
     * 快速创建包含指定元素的 LinkedHashSet(保持插入顺序)
     */
    @SafeVarargs
    public static <T> Set<T> ofLinked(T... items) {
        Set<T> set = LinkedHashSet.newLinkedHashSet(items.length);
        Collections.addAll(set, items);
        return set;
    }

    // 字符串拼接

    /**
     * 将多个对象拼接为逗号分隔的字符串,null 元素会输出 "null"
     */
    public static String toString(Object... objs) {
        if (objs == null || objs.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < objs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(objs[i]);
        }
        return sb.toString();
    }

    /**
     * 将可迭代对象中的所有元素连接为逗号分隔的字符串,null 元素视为空字符串
     */
    public static String join(Iterable<?> iterable) {
        if (iterable == null) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(",");
        for (Object item : iterable) {
            joiner.add(item == null ? "" : item.toString());
        }
        return joiner.toString();
    }

    // 集合运算

    /**
     * 求两个 Set 的交集(元素同时存在于 a 和 b),返回新的 HashSet,不修改原 Set
     */
    public static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        if (isNullOrEmpty(a) || isNullOrEmpty(b)) {
            return new HashSet<>();
        }
        Set<T> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    /**
     * 求两个 Set 的并集,返回新的 HashSet,不修改原 Set
     */
    public static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> result = new HashSet<>();
        if (a != null) {
            result.addAll(a);
        }
        if (b != null) {
            result.addAll(b);
        }
        return result;
    }

    /**
     * 求两个 Set 的差集(存在于 a 但不存在于 b 的元素),返回新的 HashSet,不修改原 Set
     */
    public static <T> Set<T> difference(Set<T> a, Set<T> b) {
        if (isNullOrEmpty(a)) {
            return new HashSet<>();
        }
        Set<T> result = new HashSet<>(a);
        if (b != null) {
            result.removeAll(b);
        }
        return result;
    }

    // 合并

    /**
     * 合并多个 Set 到一个新的 HashSet 中(不修改原 Set),自动去重
     */
    @SafeVarargs
    public static <T> Set<T> merge(Set<T> first, Set<T>... others) {
        Set<T> result = new HashSet<>();
        if (first != null) {
            result.addAll(first);
        }
        if (others != null) {
            for (Set<T> other : others) {
                if (other != null) {
                    result.addAll(other);
                }
            }
        }
        return result;
    }

    /**
     * 使用 Supplier 创建新的 Set 容器,再将多个 Set 合并进去
     */
    @SafeVarargs
    public static <T> Set<T> merge(Supplier<Set<T>> supplier, Set<T> first, Set<T>... others) {
        Set<T> result = supplier.get();
        if (first != null) {
            result.addAll(first);
        }
        if (others != null) {
            for (Set<T> other : others) {
                if (other != null) {
                    result.addAll(other);
                }
            }
        }
        return result;
    }

}
