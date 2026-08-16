package com.bingbaihanji.test.cache;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 带过期时间的缓存
 *
 * @param <K> key 类型
 * @param <V> value 类型
 */
public class TtlCache<K, V> implements Cache<K, V> {

    private final ConcurrentHashMap<K, Entry<V>> cache = new ConcurrentHashMap<>();  // 缓存数据存储

    private final long defaultTtlMillis;  // 默认过期时间(毫秒)

    private final LongAdder hitCount = new LongAdder();  // 命中计数

    private final LongAdder missCount = new LongAdder();  // 未命中计数

    public TtlCache(Duration defaultTtl) {
        if (defaultTtl == null || defaultTtl.isNegative() || defaultTtl.isZero()) {
            throw new IllegalArgumentException("defaultTtl must be positive");
        }
        this.defaultTtlMillis = defaultTtl.toMillis();
    }

    @Override
    public V get(K key) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Entry<V> entry = cache.get(key);
            if (entry == null) {
                missCount.increment();
                return null;
            }
            if (isExpired(entry, System.currentTimeMillis())) {
                cache.remove(key, entry);
                if (attempt == 0) {
                    continue;
                }
                missCount.increment();
                return null;
            }
            hitCount.increment();
            return entry.value;
        }
        missCount.increment();
        return null;
    }

    // 使用默认 TTL 写入
    @Override
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }
        Entry<V> old = cache.put(key, new Entry<>(value, System.currentTimeMillis() + defaultTtlMillis));
        return old == null ? null : old.value;
    }

    // 使用指定 TTL 写入
    public void put(K key, V value, Duration ttl) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        cache.put(key, new Entry<>(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    // 删除条目并返回旧值
    @Override
    public V remove(K key) {
        Entry<V> entry = cache.remove(key);
        return entry == null ? null : entry.value;
    }

    // 扫描并清理已经过期的条目
    public int cleanExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<K, Entry<V>>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> entry = iterator.next();
            if (isExpired(entry.getValue(), now)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    // 返回当前可用条目数 调用前会先做一次过期清理
    @Override
    public int size() {
        return estimatedSize();
    }

    // 清空全部缓存
    @Override
    public void clear() {
        cache.clear();
        hitCount.reset();
        missCount.reset();
    }

    @Override
    public double hitRate() {
        long hits = hitCount.sum();
        long total = hits + missCount.sum();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * Estimated non-expired entries (includes expired entries not yet cleaned).
     */
    public int estimatedSize() {
        return cache.size();
    }

    @Override
    public String toString() {
        return String.format("TtlCache[size=%d,hits=%d,misses=%d,hitRate=%.2f%%]", estimatedSize(), hitCount.sum(),
                missCount.sum(), hitRate() * 100);
    }

    // 判断单个条目是否已经到达过期时间
    private boolean isExpired(Entry<V> entry, long now) {
        return now >= entry.expireAt;
    }

    private static final class Entry<V> {

        final V value;  // 缓存值

        final long expireAt;  // 过期时间戳(毫秒)

        Entry(V value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

    }

}
