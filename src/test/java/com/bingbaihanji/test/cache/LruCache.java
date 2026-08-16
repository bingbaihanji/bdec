package com.bingbaihanji.test.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存缓存,持有有限数量值的强引用 每次访问某个值时,该值会被移动到队列头部 当向已满的缓存添加新值时,队列尾部的值将被逐出,并可能成为垃圾回收的候选
 * <p>
 * 如果缓存的值需要显式释放资源,请重写 {@link #entryRemoved}
 * <p>
 * 如果缓存未命中时需要按需计算对应键的值,请重写 {@link #create} 这可以简化调用代码,使其即使缓存未命中也能假定总会返回一个值
 * <p>
 * 默认情况下,缓存大小以条目数计量 重写 {@link #sizeOf} 可以使用不同的单位 例如,该缓存限制为 4MiB 的位图: <pre>   {@code
 *   int cacheSize = 4 * 1024 * 1024;  // 4MiB
 *   LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(cacheSize) {
 *       protected int sizeOf(String key, Bitmap value) {
 *           return value.getByteCount(); 
 *       }
 *   }}</pre>
 * <p>
 * 此类是线程安全的 如需原子地执行多个缓存操作,可在缓存对象上同步: <pre>   {@code
 *   synchronized (cache) {
 *     if (cache.get(key) == null) {
 *         cache.put(key, value); 
 *     }
 *   }}</pre>
 * <p>
 * 此类不允许使用 null 作为键或值 从 {@link #get},{@link #put} 或 {@link #remove} 返回 null 是明确的:表示该键不在缓存中
 * <p>
 * 此类源自 Android 3.1(Honeycomb MR1) 对于早期版本,可作为 Android 支持库的一部分使用
 *
 * @author bingbaihanji
 * @date 2026-07-10 14:31:39
 * @description 内存缓存
 */
public class LruCache<K, V> implements Cache<K, V> {

    private final LinkedHashMap<K, V> map;  // 底层存储(accessOrder=true 实现 LRU)

    /** 此缓存的当前大小(以单位计),不一定是元素个数 */
    // 当前总大小(受 sizeOf() 影响)
    private int size;

    // 最大容量上限
    private int maxSize;

    // put 调用次数
    private int putCount;

    // create 调用次数
    private int createCount;

    // 淘汰次数
    private int evictionCount;

    // 命中次数
    private int hitCount;

    // 未命中次数
    private int missCount;

    /**
     * @param maxSize 对于未重写 {@link #sizeOf} 的缓存,此为缓存的最大条目数 对于其他缓存,此为缓存中所有条目大小的最大总和
     */
    public LruCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<K, V>(0, 0.75f, true);
    }

    /**
     * 设置缓存的大小
     * @param maxSize 新的最大大小
     */
    public void resize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }

        synchronized (this) {
            this.maxSize = maxSize;
        }
        trimToSize(maxSize);
    }

    /**
     * 如果缓存中存在 {@code key} 对应的值,或者可以通过 {@code #create} 创建, 则返回该值 如果返回了某个值,它会移动到队列头部
     * 如果值未被缓存且无法创建,则返回 null
     */
    @Override
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V mapValue;
        synchronized (this) {
            mapValue = map.get(key);
            if (mapValue != null) {
                hitCount++;
                return mapValue;
            }
            missCount++;
        }

        /*
         * 尝试创建值 此过程可能耗时较长,且当 create() 返回时,map 可能已发生变化 如果 create() 工作期间有冲突的值被添加到 map
         * 中,我们将保留 map 中的值, 并释放新创建的值
         */

        V createdValue = create(key);
        if (createdValue == null) {
            return null;
        }

        synchronized (this) {
            createCount++;
            mapValue = map.put(key, createdValue);

            if (mapValue != null) {
                // 存在冲突,撤销刚才的 put 操作
                map.put(key, mapValue);
            } else {
                size += safeSizeOf(key, createdValue);
            }
        }

        if (mapValue != null) {
            entryRemoved(false, key, createdValue, mapValue);
            return mapValue;
        } else {
            trimToSize(maxSize);
            return createdValue;
        }
    }

    /**
     * 对齐日常习惯接口名称 缓存 {@code key} 对应的 {@code value} 该值会移动到队列头部
     */
    public V set(K key, V value) {
        return put(key, value);
    }

    /**
     * 缓存 {@code key} 对应的 {@code value} 该值会移动到队列头部
     * @return 之前与 {@code key} 关联的值,如果没有则返回 null
     */
    @Override
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }

        V previous;
        synchronized (this) {
            putCount++;
            size += safeSizeOf(key, value);
            previous = map.put(key, value);
            if (previous != null) {
                size -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, value);
        }

        trimToSize(maxSize);
        return previous;
    }

    /**
     * 移除最旧的条目,直到剩余条目的总大小不超过指定大小
     * @param maxSize 返回前缓存允许的最大大小 传入 -1 会逐出所有条目(包括大小为 0 的)
     */
    public void trimToSize(int maxSize) {
        while (true) {
            K key;
            V value;
            synchronized (this) {
                if (size < 0 || (map.isEmpty() && size != 0)) {
                    throw new IllegalStateException(getClass().getName() + ".sizeOf() 报告的结果不一致!");
                }

                if (size <= maxSize) {
                    break;
                }

                Map.Entry<K, V> toEvict = eldest();
                if (toEvict == null) {
                    break;
                }

                key = toEvict.getKey();
                value = toEvict.getValue();
                map.remove(key);
                size -= safeSizeOf(key, value);
                evictionCount++;
            }

            entryRemoved(true, key, value, null);
        }
    }

    /**
     * 获取最久未访问的条目(即队列头部)
     * @return 最久未访问的条目,如果 map 为空则返回 null
     */
    private Map.Entry<K, V> eldest() {
        // 标准 Java 的 LinkedHashMap 没有 eldest() 方法,改用迭代器获取第一个元素
        if (map.isEmpty()) {
            return null;
        }
        return map.entrySet().iterator().next();
    }

    /**
     * 如果存在,移除 {@code key} 对应的条目
     * @return 之前与 {@code key} 关联的值,如果没有则返回 null
     */
    @Override
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V previous;
        synchronized (this) {
            previous = map.remove(key);
            if (previous != null) {
                size -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, null);
        }

        return previous;
    }

    /**
     * 当条目被逐出或移除时调用此方法 在值被逐出以腾出空间,通过调用 {@link #remove} 移除或通过调用 {@link #put} 替换时,会调用此方法
     * 默认实现不做任何事
     *
     * <p>
     * 此方法在无同步的情况下调用:其他线程可能在此方法执行期间访问缓存
     * @param evicted 如果条目正被移除以腾出空间则为 true 如果移除是由 {@link #put} 或 {@link #remove} 引起的则为
     * false
     * @param newValue 如果存在,则为 {@code key} 的新值 如果非 null,则此次移除是由 {@link #put} 或
     * {@link #get} 引起的 否则是由逐出或 {@link #remove} 引起的
     */
    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {
    }

    /**
     * 在缓存未命中后调用,用于计算对应键的值 返回计算出的值,如果无法计算则返回 null 默认实现返回 null
     *
     * <p>
     * 此方法在无同步的情况下调用:其他线程可能在此方法执行期间访问缓存
     *
     * <p>
     * 如果此方法返回时 {@code key} 对应的值已存在于缓存中,则创建的值将通过 {@link #entryRemoved} 被释放并丢弃
     * 当多个线程同时请求同一个键(导致多次创建值), 或一个线程调用 {@link #put} 而另一个线程正在为同一个键创建值时,可能发生这种情况
     */
    protected V create(K key) {
        return null;
    }

    private int safeSizeOf(K key, V value) {
        int result = sizeOf(key, value);
        if (result < 0) {
            throw new IllegalStateException("Negative size: " + key + "=" + value);
        }
        return result;
    }

    /**
     * 返回用户自定义单位下 {@code key} 和 {@code value} 对应条目的大小 默认实现返回 1,因此 size 就是条目数,maxSize
     * 就是最大条目数
     *
     * <p>
     * 条目在缓存中时,其大小不得改变
     */
    protected int sizeOf(K key, V value) {
        return 1;
    }

    /**
     * 清空缓存,对每个被移除的条目调用 {@link #entryRemoved}
     */
    public final void evictAll() {
        trimToSize(-1);  // -1 会逐出所有大小为 0 的条目,最终清空
    }

    /**
     * 清空缓存,重置所有统计计数
     */
    @Override
    public synchronized final void clear() {
        evictAll();
        hitCount = 0;
        missCount = 0;
        putCount = 0;
        createCount = 0;
        evictionCount = 0;
    }

    /**
     * 对于未重写 {@link #sizeOf} 的缓存,返回缓存中的条目数 对于其他缓存,返回缓存中所有条目大小的总和
     */
    @Override
    public synchronized final int size() {
        return size;
    }

    /**
     * 对于未重写 {@link #sizeOf} 的缓存,返回缓存的最大条目数 对于其他缓存,返回缓存中所有条目大小的最大总和
     */
    public synchronized final int maxSize() {
        return maxSize;
    }

    /**
     * 返回 {@link #get} 返回已存在于缓存中的值的次数
     */
    public synchronized final int hitCount() {
        return hitCount;
    }

    /**
     * 返回 {@link #get} 返回 null 或需要创建新值的次数
     */
    public synchronized final int missCount() {
        return missCount;
    }

    /**
     * 返回缓存命中率(0.0 ~ 1.0)
     */
    @Override
    public synchronized final double hitRate() {
        int total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    /**
     * 返回 {@link #create(Object)} 返回值的次数
     */
    public synchronized final int createCount() {
        return createCount;
    }

    /**
     * 返回 {@link #put} 被调用的次数
     */
    public synchronized final int putCount() {
        return putCount;
    }

    /**
     * 返回被逐出的值的数量
     */
    public synchronized final int evictionCount() {
        return evictionCount;
    }

    /**
     * 返回当前缓存内容的副本,按访问顺序从最近最少访问到最近最多访问排序
     */
    public synchronized final Map<K, V> snapshot() {
        return new LinkedHashMap<K, V>(map);
    }

    @Override
    public synchronized final String toString() {
        int accesses = hitCount + missCount;
        int hitPercent = accesses != 0 ? (100 * hitCount / accesses) : 0;
        return String.format("LruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]", maxSize, hitCount, missCount,
                hitPercent);
    }

}
