package com.bingbaihanji.test.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支持 TTL(最大存活时间)和 TTI(空闲时间)的 LRU 缓存
 *
 * <p>
 * 扩展 {@link LruCache},为每个条目记录写入时间和最后访问时间 通过惰性采样清理(get/put 时检查)淘汰过期条目,避免额外的后台线程
 * </p>
 *
 * <h3>线程安全</h3>
 * <ul>
 * <li>{@link #get(Object)} — 线程安全,与父类持有同一把内部锁,将取值与过期检查原子化执行</li>
 * <li>{@link #put(Object, Object)} — 线程安全,先采样淘汰过期条目再委托父类写操作</li>
 * <li>{@link #remove(Object)} — 线程安全,委托父类并同步清理元数据</li>
 * <li>{@link #size()} / {@link #maxSize()} 等只读方法 — 线程安全,委托父类</li>
 * <li>{@link #evictExpired()} — 可在任意线程调用,每次最多采样 {@value #EVICT_SAMPLE_SIZE} 个条目</li>
 * </ul>
 *
 * @param <K> key 类型
 * @param <V> value 类型
 * @author 冰白寒祭
 * @since 2026-07-22
 */
public class TtlLruCache<K, V> extends LruCache<K, V> implements Cache<K, V> {

    /**
     * 每次 put 操作采样淘汰过期条目的最大数量 采用惰性采样式淘汰,避免每次 put 全量扫描 metaMap,控制性能开销
     */
    private static final int EVICT_SAMPLE_SIZE = 16;

    private final long ttlMillis;  // 最大存活时间(毫秒)

    private final long ttiMillis;  // 最大空闲时间(毫秒), 0=不限制

    private final ConcurrentHashMap<K, EntryMeta> metaMap = new ConcurrentHashMap<>();  // 条目时间元数据映射

    /**
     * 创建仅支持 TTL(不存在 TTI)的缓存
     * @param maxSize 最大缓存条目数
     * @param ttl 条目自创建后的最大存活时间
     */
    public TtlLruCache(int maxSize, Duration ttl) {
        this(maxSize, ttl, Duration.ZERO);
    }

    /**
     * 创建同时支持 TTL 和 TTI 的缓存
     * @param maxSize 最大缓存条目数
     * @param ttl 条目自创建后的最大存活时间(Duration.ZERO 表示无限制)
     * @param tti 条目自上次访问后的最大空闲时间(Duration.ZERO 表示无限制)
     */
    public TtlLruCache(int maxSize, Duration ttl, Duration tti) {
        super(maxSize);
        Objects.requireNonNull(ttl, "ttl 不能为 null");
        Objects.requireNonNull(tti, "tti 不能为 null");
        this.ttlMillis = ttl.toMillis();
        this.ttiMillis = tti.toMillis();
    }

    /**
     * 获取 key 对应的值,并检查是否过期
     *
     * <p>
     * 此方法与父类持相同的 {@code synchronized(this)} 锁,保证:
     * <ul>
     * <li>取值与过期检查是原子操作,不会返回已过期的值</li>
     * <li>与父类的逐出操作(trimToSize)协调,避免检查与淘汰之间的竞态窗口</li>
     * </ul>
     *
     * <p>
     * 若 TTI 已启用且未过期,则更新最后访问时间
     * </p>
     * @param key 缓存键
     * @return 未过期的值,若键不存在或值已过期则返回 {@code null}
     */
    @Override
    public final V get(K key) {
        synchronized (this) {
            V value = super.get(key);
            if (value == null) {
                return null;
            }

            EntryMeta meta = metaMap.get(key);
            if (meta != null && isExpired(meta, System.currentTimeMillis())) {
                // 条目已过期 —— 已被 super.get 计为命中父类的计数器为 private,
                // 无法直接修正已知限制:TTL 过期条目会被计入 hitCount
                metaMap.remove(key);
                super.remove(key);
                return null;
            }

            // 更新最后访问时间(用于 TTI)
            if (meta != null && ttiMillis > 0) {
                meta.setLastAccessTime(System.currentTimeMillis());
            }

            return value;
        }
    }

    /**
     * 缓存 key 对应的 value
     *
     * <p>
     * 在写入前以采样方式淘汰一批过期条目(最多 {@value #EVICT_SAMPLE_SIZE} 个), 避免全量扫描带来的性能开销
     * </p>
     * @param key 缓存键
     * @param value 缓存值
     * @return 之前与该键关联的值,若不存在则返回 {@code null}
     */
    @Override
    public final V put(K key, V value) {
        evictExpired();
        synchronized (this) {
            metaMap.put(key, new EntryMeta(System.currentTimeMillis()));
            return super.put(key, value);
        }
    }

    /**
     * 移除 key 对应的条目及其元数据
     * @param key 缓存键
     * @return 之前与该键关联的值,若不存在则返回 {@code null}
     */
    @Override
    public final V remove(K key) {
        synchronized (this) {
            metaMap.remove(key);
            return super.remove(key);
        }
    }

    /**
     * 条目被移除时的回调 当条目因 LRU 淘汰被移除时,同步清理对应的元数据
     */
    @Override
    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {
        if (evicted) {
            metaMap.remove(key);
        }
    }

    /**
     * 以采样方式淘汰过期条目
     *
     * <p>
     * 每次调用最多检查 {@value #EVICT_SAMPLE_SIZE} 个条目(迭代 {@link ConcurrentHashMap#entrySet()}
     * 的弱一致性视图), 避免在高并发写入场景下全量扫描带来的性能开销 过期条目最终会在后续的 get/put/采样 中被逐步清理
     * </p>
     */
    private void evictExpired() {
        if (ttlMillis <= 0 && ttiMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        int sampled = 0;
        for (Map.Entry<K, EntryMeta> entry : metaMap.entrySet()) {
            if (sampled >= EVICT_SAMPLE_SIZE) {
                break;
            }
            sampled++;
            if (isExpired(entry.getValue(), now)) {
                remove(entry.getKey());
            }
        }
    }

    /**
     * 判断条目是否已过期
     * @param meta 条目元数据
     * @param now 当前时间戳(毫秒)
     * @return 若 TTL 或 TTI 任意一项超时则返回 {@code true}
     */
    private boolean isExpired(EntryMeta meta, long now) {
        if (ttlMillis > 0 && (now - meta.createTime) > ttlMillis) {
            return true;
        }
        return ttiMillis > 0 && (now - meta.getLastAccessTime()) > ttiMillis;
    }

    /**
     * 条目的时间元数据 使用普通类而非 record,因为 {@code lastAccessTime} 需要高频可变更新 使用 {@code volatile}
     * 确保跨线程可见性
     */
    private static final class EntryMeta {

        final long createTime;  // 创建时间戳(毫秒)

        volatile long lastAccessTime;  // 最后访问时间戳(毫秒), volatile保证跨线程可见

        EntryMeta(long createTime) {
            this.createTime = createTime;
            this.lastAccessTime = createTime;
        }

        long getLastAccessTime() {
            return lastAccessTime;
        }

        void setLastAccessTime(long time) {
            this.lastAccessTime = time;
        }

    }

}
