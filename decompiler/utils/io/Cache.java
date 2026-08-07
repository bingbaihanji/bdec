package com.bingbaihanji.bdec.decompiler.utils.io;

/**
 * 缓存通用接口,定义了键值对缓存的核心操作
 *
 * <p>
 * 该接口不强制规定具体的缓存策略(如过期,淘汰,刷新等),由实现类自行决定 建议实现类明确说明以下行为:
 * <ul>
 * <li>是否允许 {@code null} 键或 {@code null} 值; </li>
 * <li>是否线程安全; </li>
 * <li>是否支持持久化,分布式等扩展特性</li>
 * </ul>
 *
 * <p>
 * 典型的缓存实现可能基于内存(如 {@link java.util.concurrent.ConcurrentHashMap}), 外部存储(如 Redis)或混合方案
 *
 * @param <K> 键的类型
 * @param <V> 值的类型
 * @author 冰白寒祭
 * @since 2026-07-24
 */
public interface Cache<K, V> {

    /**
     * 根据指定的键获取缓存值
     */
    V get(K key);

    /**
     * 将指定的键值对存入缓存
     */
    V put(K key, V value);

    /**
     * 从缓存中移除指定键的映射关系
     */
    V remove(K key);

    /**
     * 返回当前缓存中存储的键值对数量
     */
    int size();

    /**
     * 清空缓存
     */
    void clear();

    /**
     * 返回缓存的命中率(Hit Rate),即缓存命中次数占总访问次数的比例
     */
    double hitRate();

    /**
     * 判断缓存中是否包含指定的键
     */
    default boolean containsKey(K key) {
        // 注意:如果允许 null 值,此默认实现会将 null 值视为不存在,需子类重写
        return get(key) != null;
    }

    /**
     * 如果指定键尚未关联值(或关联的值为 null),则将其与给定值关联,并返回 null;  否则返回当前关联的值
     */
    default V putIfAbsent(K key, V value) {
        V existing = get(key);
        if (existing == null) {
            return put(key, value);
        }
        return existing;
    }

}