package com.bingbaihanji.test.collection;

import java.lang.reflect.Array;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ArrayMap 的集合视图适配器
 *
 * <p>
 * {@link ArrayMap} 的核心存储不是链表或哈希桶,而是紧凑数组JDK 的 {@link Map} 接口要求暴露
 * {@link Map#entrySet()},{@link Map#keySet()} 和 {@link Map#values()}
 * 三个视图,本类把数组下标操作适配为标准集合视图
 *
 * <p>
 * 本类是包内实现细节,不对外暴露所有视图都是实时视图,对视图的删除会直接作用到原 Map
 *
 * @param <K> key 类型
 * @param <V> value 类型
 * @author bingbaihanji
 * @since 1.0
 */
abstract class MapCollections<K, V> {

    /**
     * key 在 Entry 中的偏移
     */
    static final int INDEX_KEY = 0;

    /**
     * value 在 Entry 中的偏移
     */
    static final int INDEX_VALUE = 1;

    private EntrySet entrySet;

    private KeySet keySet;

    private ValuesCollection values;

    /**
     * 判断 map 是否包含 collection 中的全部 key
     * @param map map
     * @param collection key 集合
     * @return 全部包含时返回 true
     */
    static <K, V> boolean containsAllHelper(Map<K, V> map, Collection<?> collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        for (Object object : collection) {
            if (!map.containsKey(object)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从 map 中删除 collection 中出现的全部 key
     * @param map map
     * @param collection key 集合
     * @return map 发生变化时返回 true
     */
    static <K, V> boolean removeAllHelper(Map<K, V> map, Collection<?> collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        int oldSize = map.size();
        for (Object object : collection) {
            map.remove(object);
        }
        return oldSize != map.size();
    }

    /**
     * 只保留 collection 中出现的 key
     * @param map map
     * @param collection key 集合
     * @return map 发生变化时返回 true
     */
    static <K, V> boolean retainAllHelper(Map<K, V> map, Collection<?> collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        int oldSize = map.size();
        map.keySet().removeIf(k -> !collection.contains(k));
        return oldSize != map.size();
    }

    /**
     * Set 等价性判断
     * @param set 当前 Set
     * @param object 比较对象
     * @return 内容相同时返回 true
     */
    static boolean equalsSetHelper(Set<?> set, Object object) {
        if (set == object) {
            return true;
        }
        if (!(object instanceof Set<?> other)) {
            return false;
        }
        try {
            return set.size() == other.size() && set.containsAll(other);
        } catch (ClassCastException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * 返回 Map 当前大小
     * @return Entry 数量
     */
    abstract int colGetSize();

    /**
     * 根据 Entry 下标和偏移读取 key 或 value
     * @param index Entry 下标
     * @param offset {@link #INDEX_KEY} 或 {@link #INDEX_VALUE}
     * @return key 或 value
     */
    abstract Object colGetEntry(int index, int offset);

    /**
     * 查找 key 下标
     * @param key key
     * @return 存在时返回下标,不存在时返回负数
     */
    abstract int colIndexOfKey(Object key);

    /**
     * 查找 value 下标
     * @param value value
     * @return 存在时返回下标,不存在时返回 -1
     */
    abstract int colIndexOfValue(Object value);

    /**
     * 返回原始 Map
     * @return 原始 Map
     */
    abstract Map<K, V> colGetMap();

    /**
     * 写入一个 Entry
     * @param key key
     * @param value value
     */
    abstract void colPut(K key, V value);

    /**
     * 修改指定下标的 value
     * @param index Entry 下标
     * @param value 新 value
     * @return 旧 value
     */
    abstract V colSetValue(int index, V value);

    /**
     * 删除指定下标的 Entry
     * @param index Entry 下标
     */
    abstract void colRemoveAt(int index);

    /**
     * 清空原始 Map
     */
    abstract void colClear();

    /**
     * 返回原始 Map 的结构性修改次数
     * @return 修改次数
     */
    abstract int colGetModCount();

    /**
     * 返回 entrySet 视图
     * @return entrySet
     */
    Set<Map.Entry<K, V>> getEntrySet() {
        if (this.entrySet == null) {
            this.entrySet = new EntrySet();
        }
        return this.entrySet;
    }

    /**
     * 返回 keySet 视图
     * @return keySet
     */
    Set<K> getKeySet() {
        if (this.keySet == null) {
            this.keySet = new KeySet();
        }
        return this.keySet;
    }

    /**
     * 返回 values 视图
     * @return values
     */
    Collection<V> getValues() {
        if (this.values == null) {
            this.values = new ValuesCollection();
        }
        return this.values;
    }

    /**
     * 将指定偏移的数据复制到 Object 数组
     * @param offset key 或 value 偏移
     * @return 数组
     */
    Object[] toArrayHelper(int offset) {
        int size = colGetSize();
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) {
            result[i] = colGetEntry(i, offset);
        }
        return result;
    }

    /**
     * 将指定偏移的数据复制到指定类型数组
     * @param array 目标数组
     * @param offset key 或 value 偏移
     * @return 数组
     */
    @SuppressWarnings("unchecked")
    <T> T[] toArrayHelper(T[] array, int offset) {
        int size = colGetSize();
        T[] result = array;
        if (result.length < size) {
            result = (T[]) Array.newInstance(result.getClass().getComponentType(), size);
        }
        for (int i = 0; i < size; i++) {
            result[i] = (T) colGetEntry(i, offset);
        }
        if (result.length > size) {
            result[size] = null;
        }
        return result;
    }

    /**
     * entrySet 视图
     */
    private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {

        @Override
        public int size() {
            return colGetSize();
        }

        @Override
        public void clear() {
            colClear();
        }

        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return ArrayMapIterator.entries(MapCollections.this);
        }

        @Override
        public boolean contains(Object object) {
            if (!(object instanceof Map.Entry<?, ?> entry)) {
                return false;
            }
            int index = colIndexOfKey(entry.getKey());
            return index >= 0 && Objects.equals(colGetEntry(index, INDEX_VALUE), entry.getValue());
        }

        @Override
        public boolean remove(Object object) {
            if (!(object instanceof Map.Entry<?, ?> entry)) {
                return false;
            }
            int index = colIndexOfKey(entry.getKey());
            if (index < 0 || !Objects.equals(colGetEntry(index, INDEX_VALUE), entry.getValue())) {
                return false;
            }
            colRemoveAt(index);
            return true;
        }

        @Override
        public boolean containsAll(Collection<?> collection) {
            for (Object object : collection) {
                if (!contains(object)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object object) {
            return equalsSetHelper(this, object);
        }

        @Override
        public int hashCode() {
            int result = 0;
            for (int i = colGetSize() - 1; i >= 0; i--) {
                Object key = colGetEntry(i, INDEX_KEY);
                Object value = colGetEntry(i, INDEX_VALUE);
                result += (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
            }
            return result;
        }

        @Override
        public Object[] toArray() {
            int size = colGetSize();
            Object[] result = new Object[size];
            for (int i = 0; i < size; i++) {
                result[i] = entrySnapshotAt(i);
            }
            return result;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] array) {
            int size = colGetSize();
            T[] result = array;
            if (result.length < size) {
                result = (T[]) Array.newInstance(result.getClass().getComponentType(), size);
            }
            for (int i = 0; i < size; i++) {
                result[i] = (T) entrySnapshotAt(i);
            }
            if (result.length > size) {
                result[size] = null;
            }
            return result;
        }

        private Map.Entry<K, V> entrySnapshotAt(int index) {
            @SuppressWarnings("unchecked")
            K key = (K) colGetEntry(index, INDEX_KEY);
            @SuppressWarnings("unchecked")
            V value = (V) colGetEntry(index, INDEX_VALUE);
            return new AbstractMap.SimpleEntry<>(key, value);
        }

    }

    /**
     * keySet 视图
     */
    private final class KeySet extends AbstractSet<K> {

        @Override
        public int size() {
            return colGetSize();
        }

        @Override
        public void clear() {
            colClear();
        }

        @Override
        public Iterator<K> iterator() {
            return ArrayMapIterator.keys(MapCollections.this);
        }

        @Override
        public boolean contains(Object object) {
            return colIndexOfKey(object) >= 0;
        }

        @Override
        public boolean remove(Object object) {
            int index = colIndexOfKey(object);
            if (index < 0) {
                return false;
            }
            colRemoveAt(index);
            return true;
        }

        @Override
        public boolean containsAll(Collection<?> collection) {
            return containsAllHelper(colGetMap(), collection);
        }

        @Override
        public boolean removeAll(Collection<?> collection) {
            return removeAllHelper(colGetMap(), collection);
        }

        @Override
        public boolean retainAll(Collection<?> collection) {
            return retainAllHelper(colGetMap(), collection);
        }

        @Override
        public Object[] toArray() {
            return toArrayHelper(INDEX_KEY);
        }

        @Override
        public <T> T[] toArray(T[] array) {
            return toArrayHelper(array, INDEX_KEY);
        }

        @Override
        public boolean equals(Object object) {
            return equalsSetHelper(this, object);
        }

        @Override
        public int hashCode() {
            int result = 0;
            for (int i = colGetSize() - 1; i >= 0; i--) {
                Object key = colGetEntry(i, INDEX_KEY);
                result += key == null ? 0 : key.hashCode();
            }
            return result;
        }

    }

    /**
     * values 视图
     */
    private final class ValuesCollection extends AbstractCollection<V> {

        @Override
        public int size() {
            return colGetSize();
        }

        @Override
        public void clear() {
            colClear();
        }

        @Override
        public Iterator<V> iterator() {
            return ArrayMapIterator.values(MapCollections.this);
        }

        @Override
        public boolean contains(Object object) {
            return colIndexOfValue(object) >= 0;
        }

        @Override
        public boolean remove(Object object) {
            int index = colIndexOfValue(object);
            if (index < 0) {
                return false;
            }
            colRemoveAt(index);
            return true;
        }

        @Override
        public boolean removeAll(Collection<?> collection) {
            Objects.requireNonNull(collection, "collection 不能为 null");
            boolean changed = false;
            for (int i = colGetSize() - 1; i >= 0; i--) {
                if (collection.contains(colGetEntry(i, INDEX_VALUE))) {
                    colRemoveAt(i);
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public boolean retainAll(Collection<?> collection) {
            Objects.requireNonNull(collection, "collection 不能为 null");
            boolean changed = false;
            for (int i = colGetSize() - 1; i >= 0; i--) {
                if (!collection.contains(colGetEntry(i, INDEX_VALUE))) {
                    colRemoveAt(i);
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public Object[] toArray() {
            return toArrayHelper(INDEX_VALUE);
        }

        @Override
        public <T> T[] toArray(T[] array) {
            return toArrayHelper(array, INDEX_VALUE);
        }

    }

}
