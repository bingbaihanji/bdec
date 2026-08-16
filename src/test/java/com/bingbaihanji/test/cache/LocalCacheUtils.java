package com.bingbaihanji.test.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 本地缓存建造器,封装项目自研缓存工具
 *
 * <p>
 * 适用于单机场景下的临时数据缓存,如字典缓存,配置缓存等 分布式场景请使用 Redis
 * </p>
 *
 * @author 冰白寒祭
 * @since 2026-06-12
 */
public final class LocalCacheUtils {

    private LocalCacheUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 创建基于时间的本地缓存(按最长存活时间过期)
     * @param maxCapacity 最大容量
     * @param expireDuration 过期时长
     * @param <K> Key 类型
     * @param <V> Value 类型
     * @return TtlLruCache 实例
     */
    public static <K, V> TtlLruCache<K, V> newTimedCache(int maxCapacity, Duration expireDuration) {
        Objects.requireNonNull(expireDuration, "expireDuration 不能为 null");
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity 必须 > 0");
        }
        return new TtlLruCache<>(maxCapacity, expireDuration);
    }

    /**
     * 创建基于时间的本地缓存(按无访问时长过期)
     * @param maxCapacity 最大容量
     * @param expireDuration 无访问过期时长
     * @param <K> Key 类型
     * @param <V> Value 类型
     * @return TtlLruCache 实例
     */
    public static <K, V> TtlLruCache<K, V> newLRUCache(int maxCapacity, Duration expireDuration) {
        Objects.requireNonNull(expireDuration, "expireDuration 不能为 null");
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity 必须 > 0");
        }
        return new TtlLruCache<>(maxCapacity, Duration.ZERO, expireDuration);
    }

    /**
     * 从缓存中获取值,不存在时通过 supplier 加载并缓存
     * @param cache 缓存实例
     * @param key 缓存键
     * @param supplier 值加载器(仅在缓存未命中时调用)
     * @param <K> Key 类型
     * @param <V> Value 类型
     * @return 缓存中的值或加载的新值
     */
    public static <K, V> V getOrPut(TtlLruCache<K, V> cache, K key, Supplier<V> supplier) {
        Objects.requireNonNull(cache, "cache 不能为 null");
        Objects.requireNonNull(key, "key 不能为 null");
        Objects.requireNonNull(supplier, "supplier 不能为 null");
        V value = cache.get(key);
        if (value == null) {
            value = supplier.get();
            if (value != null) {
                cache.put(key, value);
            }
        }
        return value;
    }

}
