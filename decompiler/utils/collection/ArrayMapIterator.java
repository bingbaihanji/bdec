package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * ArrayMap 集合视图使用的 fail-fast 迭代器
 *
 * <p>
 * 迭代器创建时会记录 Map 的结构性修改次数迭代过程中如果检测到 Map 被迭代器自身以外的方式新增,删除或清空,会抛出
 * {@link ConcurrentModificationException}
 *
 * <p>
 * fail-fast 是错误探测机制,不是并发控制机制; 并发访问仍应由调用方自行同步
 *
 * @param <K> key 类型
 * @param <V> value 类型
 * @param <E> 迭代元素类型
 * @author bingbaihanji
 * @since 1.0
 */
final class ArrayMapIterator<K, V, E> implements Iterator<E> {

    private static final int TYPE_KEY = 0;

    private static final int TYPE_VALUE = 1;

    private static final int TYPE_ENTRY = 2;

    private final MapCollections<K, V> collection;

    private final int type;

    private int expectedModCount;

    private int nextIndex;

    private int lastIndex = -1;

    private ArrayMapIterator(MapCollections<K, V> collection, int type) {
        this.collection = Objects.requireNonNull(collection, "collection 不能为 null");
        this.type = type;
        this.expectedModCount = collection.colGetModCount();
    }

    /**
     * 创建 key 迭代器
     * @param collection 集合视图适配器
     * @return key 迭代器
     */
    static <K, V> Iterator<K> keys(MapCollections<K, V> collection) {
        return new ArrayMapIterator<>(collection, TYPE_KEY);
    }

    /**
     * 创建 value 迭代器
     * @param collection 集合视图适配器
     * @return value 迭代器
     */
    static <K, V> Iterator<V> values(MapCollections<K, V> collection) {
        return new ArrayMapIterator<>(collection, TYPE_VALUE);
    }

    /**
     * 创建 entry 迭代器
     * @param collection 集合视图适配器
     * @return entry 迭代器
     */
    static <K, V> Iterator<Map.Entry<K, V>> entries(MapCollections<K, V> collection) {
        return new ArrayMapIterator<>(collection, TYPE_ENTRY);
    }

    @Override
    public boolean hasNext() {
        // 与 JDK 迭代器保持一致:hasNext() 不做并发修改检测,仅 next()/remove() 触发 fail-fast
        return this.nextIndex < this.collection.colGetSize();
    }

    @Override
    public E next() {
        checkForComodification();
        if (this.nextIndex >= this.collection.colGetSize()) {
            throw new NoSuchElementException();
        }

        int currentIndex = this.nextIndex++;
        this.lastIndex = currentIndex;
        return elementAt(currentIndex);
    }

    @Override
    public void remove() {
        checkForComodification();
        if (this.lastIndex < 0) {
            throw new IllegalStateException("必须先调用 next(),且每次 next() 后只能 remove() 一次");
        }

        this.collection.colRemoveAt(this.lastIndex);
        this.nextIndex = this.lastIndex;
        this.lastIndex = -1;
        this.expectedModCount = this.collection.colGetModCount();
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        if (this.type == TYPE_KEY) {
            return (E) this.collection.colGetEntry(index, MapCollections.INDEX_KEY);
        }
        if (this.type == TYPE_VALUE) {
            return (E) this.collection.colGetEntry(index, MapCollections.INDEX_VALUE);
        }
        return (E) new EntryView<>(this.collection, index, this.expectedModCount);
    }

    private void checkForComodification() {
        if (this.collection.colGetModCount() != this.expectedModCount) {
            throw new ConcurrentModificationException();
        }
    }

    /**
     * entrySet 迭代时返回的实时 Entry 视图
     *
     * <p>
     * 每次 {@link #next()} 都创建独立 Entry,避免 JDK {@code toArray()},{@code contains()}
     * 等集合操作拿到重复的 Entry 引用
     */
    private static final class EntryView<K, V> implements Map.Entry<K, V> {

        private final MapCollections<K, V> collection;

        private final int index;

        private final int expectedModCount;

        private EntryView(MapCollections<K, V> collection, int index, int expectedModCount) {
            this.collection = collection;
            this.index = index;
            this.expectedModCount = expectedModCount;
        }

        @Override
        @SuppressWarnings("unchecked")
        public K getKey() {
            checkValid();
            return (K) this.collection.colGetEntry(this.index, MapCollections.INDEX_KEY);
        }

        @Override
        @SuppressWarnings("unchecked")
        public V getValue() {
            checkValid();
            return (V) this.collection.colGetEntry(this.index, MapCollections.INDEX_VALUE);
        }

        @Override
        public V setValue(V value) {
            checkValid();
            return this.collection.colSetValue(this.index, value);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Map.Entry<?, ?> entry)) {
                return false;
            }
            return Objects.equals(getKey(), entry.getKey()) && Objects.equals(getValue(), entry.getValue());
        }

        @Override
        public int hashCode() {
            K key = getKey();
            V value = getValue();
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }

        private void checkValid() {
            if (this.collection.colGetModCount() != this.expectedModCount
                    || this.index >= this.collection.colGetSize()) {
                throw new ConcurrentModificationException();
            }
        }

    }

}
