package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 多值映射(一个键关联一组值),所有公开的集合视图均为不可修改,
 * 修改操作必须通过本类提供的方法进行,以保证内部计数一致.
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @param <C> 值集合类型(必须为 {@link Collection} 的子类型,且支持修改)
 * @author xDark (优化)
 */
public final class MultiMap<K, V, C extends Collection<V>> {

    private final Map<K, C> backing;               // 内部持有的 Map

    private final Function<K, C> collectionFunction; // 创建值集合的函数

    private int totalSize;                         // 所有值的总数(O(1) 获取)

    /**
     * 私有构造器,深拷贝传入的 Map 及其所有值集合.
     *
     * @param map                源 Map(可以是任意 Map,值类型为 C 或其子类)
     * @param collectionSupplier 用于创建空值集合的供给器
     */
    private MultiMap(Map<? extends K, ? extends C> map, Supplier<? extends C> collectionSupplier) {
        Objects.requireNonNull(map, "source map cannot be null");
        Objects.requireNonNull(collectionSupplier, "collectionSupplier cannot be null");

        // 深拷贝:为每个键创建新的值集合并复制所有元素
        Map<K, C> newBacking = new HashMap<>(map.size());
        int total = 0;
        for (Map.Entry<? extends K, ? extends C> entry : map.entrySet()) {
            C newCollection = collectionSupplier.get();
            newCollection.addAll(entry.getValue());
            newBacking.put(entry.getKey(), newCollection);
            total += newCollection.size();
        }

        this.backing = newBacking;
        this.collectionFunction = k -> collectionSupplier.get();
        this.totalSize = total;
    }

    /**
     * 从已有的 Map 创建一个多值映射(深拷贝).
     *
     * @param map                源 Map
     * @param collectionSupplier 值集合的供给器
     * @param <K>                键类型
     * @param <V>                值类型
     * @param <C>                值集合类型
     * @return 新的多值映射
     */
    public static <K, V, C extends Collection<V>> MultiMap<K, V, C> from(
            Map<? extends K, ? extends C> map,
            Supplier<? extends C> collectionSupplier) {
        return new MultiMap<>(map, collectionSupplier);
    }

    /**
     * 创建一个空的多值映射.
     *
     * @param collectionSupplier 值集合的供给器
     * @param <K>                键类型
     * @param <V>                值类型
     * @param <C>                值集合类型
     * @return 空的多值映射
     */
    public static <K, V, C extends Collection<V>> MultiMap<K, V, C> create(
            Supplier<? extends C> collectionSupplier) {
        return new MultiMap<>(Collections.emptyMap(), collectionSupplier);
    }

    // ==================== 基本查询 ====================

    /**
     * @return 映射中所有值的总数量(O(1))
     */
    public int size() {
        return totalSize;
    }

    /**
     * @return 映射是否为空(O(1))
     */
    public boolean isEmpty() {
        return totalSize == 0;
    }

    /**
     * 判断是否包含某个键(关联的非空集合视为存在).
     *
     * @param key 要检查的键
     * @return 如果存在且关联集合非空返回 {@code true}
     */
    public boolean containsKey(K key) {
        C collection = backing.get(key);
        return collection != null && !collection.isEmpty();
    }

    /**
     * 判断是否包含某个值(线性扫描所有值集合).
     *
     * @param value 要检查的值
     * @return 如果存在返回 {@code true}
     */
    public boolean containsValue(V value) {
        return backing.values().stream().anyMatch(c -> c.contains(value));
    }

    /**
     * 获取某个键关联的值集合(只读视图).若键不存在,则自动创建并关联一个空集合.
     *
     * @param key 键
     * @return 不可修改的 {@link Collection} 视图
     */
    public Collection<V> get(K key) {
        C collection = backing.computeIfAbsent(key, collectionFunction);
        return Collections.unmodifiableCollection(collection);
    }

    /**
     * 获取某个键关联的值集合,若不存在或为空则返回 {@code null}.
     *
     * @param key 键
     * @return 不可修改的集合视图,或 {@code null}
     */
    public Collection<V> getIfPresent(K key) {
        C collection = backing.get(key);
        if (collection == null || collection.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableCollection(collection);
    }

    /**
     * 获取某个键关联的值集合,若不存在则返回指定的默认值.
     *
     * @param key          键
     * @param defaultValue 默认值(会被原样返回,通常应为不可修改集合)
     * @return 不可修改的集合视图,或默认值
     */
    public Collection<V> getOrDefault(K key, Collection<V> defaultValue) {
        Collection<V> present = getIfPresent(key);
        return present != null ? present : defaultValue;
    }

    /**
     * 获取某个键关联的值数量(若键不存在则为 0).
     *
     * @param key 键
     * @return 该键对应的值个数
     */
    public int valueCount(K key) {
        C collection = backing.get(key);
        return collection == null ? 0 : collection.size();
    }

    // ==================== 修改操作 ====================

    /**
     * 添加一个值到指定键的集合中.
     *
     * @param key   键
     * @param value 值
     * @return 如果值被成功添加(集合发生变化)返回 {@code true}
     */
    public boolean put(K key, V value) {
        C collection = backing.computeIfAbsent(key, collectionFunction);
        if (collection.add(value)) {
            totalSize++;
            return true;
        }
        return false;
    }

    /**
     * 将一组值添加到指定键的集合中.
     *
     * @param key    键
     * @param values 要添加的值集合(为 {@code null} 或空则不做任何操作)
     * @return 如果至少有一个值被添加返回 {@code true}
     */
    public boolean putAll(K key, Collection<? extends V> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        C collection = backing.computeIfAbsent(key, collectionFunction);
        int oldSize = collection.size();
        if (collection.addAll(values)) {
            totalSize += (collection.size() - oldSize);
            return true;
        }
        return false;
    }

    /**
     * 合并另一个多值映射的所有键值对.
     *
     * @param other 另一个多值映射(允许通配符类型)
     */
    public void putAll(MultiMap<? extends K, ? extends V, ? extends C> other) {
        Objects.requireNonNull(other, "other map cannot be null");
        for (Map.Entry<? extends K, ? extends C> entry : other.backing.entrySet()) {
            putAll(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 移除指定键的某个值.
     *
     * @param key   键
     * @param value 值
     * @return 如果值被成功移除返回 {@code true}
     */
    public boolean remove(K key, V value) {
        C collection = backing.get(key);
        if (collection == null) {
            return false;
        }
        boolean removed = collection.remove(value);
        if (removed) {
            totalSize--;
            if (collection.isEmpty()) {
                backing.remove(key);
            }
        }
        return removed;
    }

    /**
     * 移除指定键关联的所有值(整组移除).
     *
     * @param key 键
     * @return 被移除的值集合(可能为 {@code null}),此后该键不再存在
     */
    public C remove(K key) {
        C collection = backing.remove(key);
        if (collection != null) {
            totalSize -= collection.size();
        }
        return collection;
    }

    /**
     * 替换指定键的值集合为新的集合(原集合被丢弃).
     *
     * @param key    键
     * @param values 新值集合(若为 {@code null} 或空,相当于移除该键)
     * @return 被替换掉的旧值集合,若原先不存在则返回 {@code null}
     */
    public C replaceValues(K key, Collection<? extends V> values) {
        C oldCollection = backing.remove(key);
        if (oldCollection != null) {
            totalSize -= oldCollection.size();
        }
        if (values != null && !values.isEmpty()) {
            C newCollection = collectionFunction.apply(key);
            newCollection.addAll(values);
            backing.put(key, newCollection);
            totalSize += newCollection.size();
            return oldCollection;
        }
        return oldCollection;
    }

    /**
     * 清空整个多值映射.
     */
    public void clear() {
        backing.clear();
        totalSize = 0;
    }

    // ==================== 视图与流 ====================

    /**
     * @return 所有键的不可修改集合视图
     */
    public Set<K> keySet() {
        return Collections.unmodifiableSet(backing.keySet());
    }

    /**
     * @return 所有值的扁平化流(只读)
     */
    public Stream<V> values() {
        return backing.values().stream().flatMap(Collection::stream);
    }

    /**
     * @return 不可修改的 Entry 集合视图,其中每个 Entry 的值集合也是不可修改的
     */
    public Set<Map.Entry<K, Collection<V>>> entrySet() {
        return new AbstractSet<Map.Entry<K, Collection<V>>>() {

            @Override
            public Iterator<Map.Entry<K, Collection<V>>> iterator() {
                Iterator<Map.Entry<K, C>> it = backing.entrySet().iterator();
                return new Iterator<Map.Entry<K, Collection<V>>>() {

                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Map.Entry<K, Collection<V>> next() {
                        Map.Entry<K, C> entry = it.next();
                        return new UnmodifiableEntry<>(entry);
                    }
                };
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }

    /**
     * 返回一个只读的 {@link Map} 视图,键映射到不可修改的集合.
     * 此视图不能修改,但会实时反映 MultiMap 的变化.
     *
     * @return 不可修改的 Map 视图
     */
    public Map<K, Collection<V>> asMap() {
        return new AbstractMap<K, Collection<V>>() {

            @Override
            public Set<Entry<K, Collection<V>>> entrySet() {
                return MultiMap.this.entrySet();
            }
        };
    }

    // ==================== 内部辅助类 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MultiMap<?, ?, ?> that)) {
            return false;
        }
        return backing.equals(that.backing);
    }

    // ==================== 标准方法重写 ====================

    @Override
    public int hashCode() {
        return backing.hashCode();
    }

    @Override
    public String toString() {
        return backing.toString();
    }

    /**
     * 不可修改的 Entry 包装器,将其值集合包装为不可修改视图.
     */
    private static class UnmodifiableEntry<K, V, C extends Collection<V>>
            implements Map.Entry<K, Collection<V>> {

        private final Map.Entry<K, C> delegate;

        UnmodifiableEntry(Map.Entry<K, C> delegate) {
            this.delegate = delegate;
        }

        @Override
        public K getKey() {
            return delegate.getKey();
        }

        @Override
        public Collection<V> getValue() {
            return Collections.unmodifiableCollection(delegate.getValue());
        }

        @Override
        public Collection<V> setValue(Collection<V> value) {
            throw new UnsupportedOperationException("entry is read-only");
        }
    }
}