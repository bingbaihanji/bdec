package com.bingbaihanji.test.cache;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 固定容量 LFU(最不经常使用)缓存
 *
 * <p>
 * 淘汰访问频率最低的条目; 频率相同时淘汰同频分组中最早插入的条目(LRU 决胜)
 * </p>
 *
 * <h3>线程安全</h3> 所有 public 方法使用 {@code synchronized} 互斥,保证线程安全
 *
 * @param <K> key 类型
 * @param <V> value 类型
 * @author 冰白寒祭
 * @since 2026-07-24
 */
public class LfuCache<K, V> implements Cache<K, V> {

    private final int capacity;  // 缓存容量上限

    private final Map<K, Node<K, V>> cache = new HashMap<>();  // 缓存数据存储

    private final Map<Integer, LinkedHashSet<K>> frequencies = new HashMap<>();  // 频率→key集合

    private int minFrequency;  // 当前最小访问频率

    private int hitCount;  // 命中次数

    private int missCount;  // 未命中次数

    private int evictionCount;  // 淘汰次数

    public LfuCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity <= 0");
        }
        this.capacity = capacity;
    }

    // 读取后顺手把频率加一 保持 LFU 统计准确
    @Override
    public synchronized V get(K key) {
        Node<K, V> node = cache.get(key);
        if (node == null) {
            missCount++;
            return null;
        }
        hitCount++;
        incrementFreq(key);
        return node.value;
    }

    // 写入新值; 满了就先淘汰当前最低频率的条目
    @Override
    public synchronized V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }
        Node<K, V> node = cache.get(key);
        if (node != null) {
            V oldValue = node.value;
            node.value = value;
            incrementFreq(key);
            return oldValue;
        }
        if (cache.size() >= capacity) {
            evict();
        }
        Node<K, V> newNode = new Node<>(key, value);
        cache.put(key, newNode);
        frequencies.computeIfAbsent(1, ignored -> new LinkedHashSet<>()).add(key);
        minFrequency = 1;
        return null;
    }

    // 手动提升某个 key 的访问频率
    private synchronized void incrementFreq(K key) {
        Node<K, V> node = cache.get(key);
        if (node == null) {
            return;
        }
        int oldFreq = node.frequency;
        int newFreq = oldFreq + 1;

        LinkedHashSet<K> keys = frequencies.get(oldFreq);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                frequencies.remove(oldFreq);
                if (minFrequency == oldFreq) {
                    // 扫描下一个有数据的最低频率桶
                    minFrequency = newFreq;  // default to new freq
                    for (int f = oldFreq + 2; f < newFreq + 10; f++) {
                        LinkedHashSet<K> next = frequencies.get(f);
                        if (next != null && !next.isEmpty()) {
                            minFrequency = f;
                            break;
                        }
                    }
                }
            }
        }
        node.frequency = newFreq;
        frequencies.computeIfAbsent(newFreq, k -> new LinkedHashSet<>()).add(key);
    }

    // 删除指定条目 并同步清理频率桶
    @Override
    public synchronized V remove(K key) {
        Node<K, V> node = cache.remove(key);
        if (node == null) {
            return null;
        }
        LinkedHashSet<K> keys = frequencies.get(node.frequency);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                frequencies.remove(node.frequency);
            }
        }
        if (cache.isEmpty()) {
            minFrequency = 0;
        }
        return node.value;
    }

    // 当前缓存中实际存放的条目数
    @Override
    public synchronized int size() {
        return cache.size();
    }

    // 缓存容量上限
    public int capacity() {
        return capacity;
    }

    // 从最小频率桶里拿出一个最早进入的 key 做淘汰
    private void evict() {
        while (minFrequency < Integer.MAX_VALUE) {
            LinkedHashSet<K> keys = frequencies.get(minFrequency);
            if (keys != null && !keys.isEmpty()) {
                K evictedKey = keys.iterator().next();
                keys.remove(evictedKey);
                if (keys.isEmpty()) {
                    frequencies.remove(minFrequency);
                }
                cache.remove(evictedKey);
                evictionCount++;
                return;
            }
            minFrequency++;
        }
    }

    @Override
    public synchronized double hitRate() {
        int total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    public synchronized int evictionCount() {
        return evictionCount;
    }

    @Override
    public synchronized void clear() {
        cache.clear();
        frequencies.clear();
        minFrequency = 0;
        hitCount = 0;
        missCount = 0;
        evictionCount = 0;
    }

    @Override
    public synchronized boolean containsKey(K key) {
        return cache.containsKey(key);
    }

    @Override
    public synchronized String toString() {
        return String.format("LfuCache[capacity=%d,size=%d,hits=%d,misses=%d,hitRate=%.0f%%]", capacity, size(),
                hitCount, missCount, hitRate() * 100);
    }

    private static final class Node<K, V> {

        final K key;  // 节点键

        V value;  // 节点值

        int frequency = 1;  // 访问频率

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

    }

}
