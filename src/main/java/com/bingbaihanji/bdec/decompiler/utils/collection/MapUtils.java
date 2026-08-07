package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Map 工具类,提供判空,空值移除,排序,值转换,合并等常用操作
 *
 * @author bingbaihanji
 * @date 2026-06-12
 */
public final class MapUtils {

    private MapUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    // 判空

    /**
     * 判断 Map 是否为 null 或无元素
     */
    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * 判断 Map 是否非 null 且有元素
     */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

    /**
     * 如果 map 为 null 或空,则返回 defaultMap,否则返回原 map
     */
    public static <K, V> Map<K, V> defaultIfEmpty(Map<K, V> map, Map<K, V> defaultMap) {
        return isNotEmpty(map) ? map : defaultMap;
    }

    /**
     * 返回不可变的空 Map
     */
    public static <K, V> Map<K, V> emptyMap() {
        return Collections.emptyMap();
    }

    // 创建

    /**
     * 根据预期大小创建 HashMap,避免扩容
     * @param expectedSize 预期元素数量
     * @return 具有合适初始容量的 HashMap
     */
    public static <K, V> HashMap<K, V> newHashMapWithExpectedSize(int expectedSize) {
        return HashMap.newHashMap(expectedSize);
    }

    /**
     * 根据预期大小创建 LinkedHashMap(保持插入顺序),避免扩容
     * @param expectedSize 预期元素数量
     * @return 具有合适初始容量的 LinkedHashMap
     */
    public static <K, V> LinkedHashMap<K, V> newLinkedHashMapWithExpectedSize(int expectedSize) {
        return LinkedHashMap.newLinkedHashMap(expectedSize);
    }

    // 转换

    /**
     * 移除值为 null 或空字符串的条目,保持原始顺序
     */
    public static Map<String, Object> removeEmptyValue(Map<String, Object> map) {
        if (isNullOrEmpty(map)) {
            return new HashMap<>();
        }
        return map.entrySet().stream().filter(entry -> {
            Object value = entry.getValue();
            if (value == null) {
                return false;
            }
            return !(value instanceof String && ((String) value).isEmpty());
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v2, LinkedHashMap::new));
    }

    /**
     * 对 Map 的所有 value 进行转换,生成新的 LinkedHashMap
     * @param map 原始 Map,不能为 null
     * @param mapper value 转换函数
     * @return 转换后的新 Map
     */
    public static <K, V, R> Map<K, R> mapValues(Map<K, V> map,
                                                java.util.function.Function<? super V, ? extends R> mapper) {
        Objects.requireNonNull(map, "map 不能为 null");
        Objects.requireNonNull(mapper, "mapper 不能为 null");
        Map<K, R> result = LinkedHashMap.newLinkedHashMap(map.size());
        map.forEach((k, v) -> result.put(k, mapper.apply(v)));
        return result;
    }

    // 排序

    /**
     * 按 Key 的自然顺序排序,null 键放到末尾
     */
    public static <K extends Comparable<? super K>, V> Map<K, V> sortMapByKey(Map<K, V> data) {
        if (isNullOrEmpty(data)) {
            return new LinkedHashMap<>();
        }
        LinkedHashMap<K, V> result = new LinkedHashMap<>();
        data.entrySet()
                .stream()
                .sorted(Map.Entry.<K, V>comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    // 合并

    /**
     * 合并多个 Map 到一个新的 HashMap 中(不修改原 Map),后者覆盖前者相同的 key
     */
    @SafeVarargs
    public static <K, V> Map<K, V> merge(Map<K, V> first, Map<K, V>... others) {
        Map<K, V> result = new HashMap<>();
        if (first != null) {
            result.putAll(first);
        }
        if (others != null) {
            for (Map<K, V> other : others) {
                if (other != null) {
                    result.putAll(other);
                }
            }
        }
        return result;
    }

    /**
     * 使用 Supplier 创建新的 Map 容器,再将多个 Map 合并进去
     */
    @SafeVarargs
    public static <K, V> Map<K, V> merge(Supplier<Map<K, V>> supplier, Map<K, V> first, Map<K, V>... others) {
        Map<K, V> result = supplier.get();
        if (first != null) {
            result.putAll(first);
        }
        if (others != null) {
            for (Map<K, V> other : others) {
                if (other != null) {
                    result.putAll(other);
                }
            }
        }
        return result;
    }

}
