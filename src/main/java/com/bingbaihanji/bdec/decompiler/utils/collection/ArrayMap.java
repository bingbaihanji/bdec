package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * 面向 Java SE / SpringBoot 的轻量级 ArrayMap
 *
 * <p>
 * 本类是一个「key → value」映射结构,设计目标是在中小规模数据下比 {@link HashMap} 占用更少内存 它参考 Android
 * {@code android.util.ArrayMap} 的数据结构思想,公开方法签名尽量与其对齐,但实现完全基于 Java SE,不依赖 Android,Lombok
 * 或任何第三方库内部使用两个平行数组保存数据:
 * <ul>
 * <li>{@code int[] hashes}:按升序保存各 key 的 hash</li>
 * <li>{@code Object[] array}:按 {@code key0,value0,key1,value1,...} 交错保存键和值</li>
 * </ul>
 *
 * <p>
 * 相比 {@link HashMap},本类用「二分查找 + 数组移位」换取更少的对象数量和更紧凑的内存布局它适合中小规模,
 * 创建频繁,读多写少的映射,例如配置项,请求上下文,元数据,DTO 附加属性等场景元素数量达到数千以上时, {@link HashMap} 仍是更好的选择
 *
 * <p>
 * <b>相较 Android 原版的现代化改造:</b>
 * <ul>
 * <li>去掉了 {@code SimpleArrayMap} 基类,核心存储直接内聚到本类,与 Android 的单类结构一致</li>
 * <li>移除了全局静态数组缓存({@code mBaseCache} / {@code mTwiceBaseCache} 及其锁) 该缓存是为 Android 受限堆减少
 * GC 而设计的,在 JVM 上属于反模式(锁竞争,内存滞留,线程安全隐患)</li>
 * <li>移除了 {@code identityHashCode} 模式:始终使用 {@link Object#hashCode()} /
 * {@link Object#equals(Object)}</li>
 * <li>移除了 Android 的 {@code Log} / {@code Slog} / {@code UtilConfig} / {@code ArrayUtils}
 * 等平台依赖</li>
 * <li>引入 {@code modCount},视图迭代器具备真正的 fail-fast 能力</li>
 * <li>采用 1.5 倍扩容(复用 {@link ContainerHelpers}),并在删除后按需收缩数组</li>
 * <li>额外实现 {@link Cloneable} / {@link Serializable},并对若干 {@link Map} 默认方法做了单次定位优化</li>
 * </ul>
 *
 * <p>
 * 本类不是线程安全的并发读写时应在外部加锁,或使用 {@link java.util.concurrent.ConcurrentHashMap} 等线程安全容器
 *
 * @param <K> key 类型
 * @param <V> value 类型
 * @author bingbaihanji
 * @since 1.0
 */
public final class ArrayMap<K, V> implements Map<K, V>, Cloneable, Serializable {

    @Serial
    private static final long serialVersionUID = 2001030720020720L;

    /**
     * 每个 Entry 在 {@link #array} 中占用两个槽位(key,value)
     */
    private static final int ENTRY_WIDTH = 2;

    /**
     * 结构性修改次数,用于支持 fail-fast 迭代器
     *
     * <p>
     * 新增,删除,清空属于结构性修改; 仅替换已有 key 的 value 不属于结构性修改
     */
    private transient int modCount;

    /**
     * Map 接口视图适配器,按需创建
     */
    private transient MapCollections<K, V> collections;

    /**
     * hash 数组,长度表示当前容量,前 {@link #size} 个元素有效,按升序排列
     */
    private int[] hashes;

    /**
     * key/value 交错数组,长度始终为 {@code hashes.length * 2}
     */
    private Object[] array;

    /**
     * 当前保存的 Entry 数量
     */
    private int size;

    /**
     * 创建一个空 ArrayMap默认容量为 0,首次写入时才分配数组
     */
    public ArrayMap() {
        this.hashes = EmptyArray.INT;
        this.array = EmptyArray.OBJECT;
    }

    /**
     * 创建指定初始容量的 ArrayMap
     * @param capacity 初始容量,不能小于 0
     */
    public ArrayMap(int capacity) {
        ContainerHelpers.checkCapacity(capacity);
        if (capacity == 0) {
            this.hashes = EmptyArray.INT;
            this.array = EmptyArray.OBJECT;
        } else {
            this.hashes = new int[capacity];
            this.array = newObjectArray(capacity);
        }
    }

    /**
     * 使用另一个 ArrayMap 的内容创建新实例
     * @param map 数据来源,允许为 null
     */
    public ArrayMap(ArrayMap<K, V> map) {
        this(map == null ? 0 : map.size);
        if (map != null) {
            putAll(map);
        }
    }

    /**
     * 使用任意标准 Map 的内容创建 ArrayMap
     *
     * <p>
     * 该构造器是对 Android 原版的附加增强,便于从 {@link HashMap} 等容器构建由于 ArrayMap 自身即为 {@link Map},当实参本身是
     * ArrayMap 时会优先匹配 {@link #ArrayMap(ArrayMap)} 走整段复制快路径,不存在歧义
     * @param map 数据来源,允许为 null
     */
    public ArrayMap(Map<? extends K, ? extends V> map) {
        this(map == null ? 0 : map.size());
        if (map != null) {
            putAll(map);
        }
    }

    /**
     * 创建 key/value 交错数组
     * @param capacity Entry 容量
     * @return Object 数组
     */
    private static Object[] newObjectArray(int capacity) {
        if (capacity > (Integer.MAX_VALUE >>> 1)) {
            throw new OutOfMemoryError("ArrayMap capacity is too large: " + capacity);
        }
        return new Object[capacity * ENTRY_WIDTH];
    }

    /**
     * 返回当前 Entry 数量
     * @return Entry 数量
     */
    @Override
    public int size() {
        return this.size;
    }

    /**
     * 判断当前 Map 是否为空
     * @return 无元素时返回 true
     */
    @Override
    public boolean isEmpty() {
        return this.size == 0;
    }

    /**
     * 返回当前内部数组容量
     *
     * <p>
     * 该值主要用于测试,调优和诊断,不等同于 {@link #size()}
     * @return 当前容量
     */
    public int capacity() {
        return this.hashes.length;
    }

    /**
     * 确保内部数组至少能容纳指定数量的 Entry
     *
     * <p>
     * 该方法只改变内部容量,不改变 Map 内容,因此不会触发 fail-fast
     * @param minimumCapacity 最小容量,不能小于 0
     */
    public void ensureCapacity(int minimumCapacity) {
        ContainerHelpers.checkCapacity(minimumCapacity);
        int oldSize = this.size;
        if (this.hashes.length < minimumCapacity) {
            resizeArrays(minimumCapacity);
        }
        if (this.size != oldSize) {
            throw new ConcurrentModificationException();
        }
    }

    /**
     * 判断是否包含指定 key
     * @param key key
     * @return 包含时返回 true
     */
    @Override
    public boolean containsKey(Object key) {
        return indexOfKey(key) >= 0;
    }

    /**
     * 判断是否包含指定 value
     *
     * <p>
     * value 没有排序信息,因此需要线性扫描
     * @param value value
     * @return 包含时返回 true
     */
    @Override
    public boolean containsValue(Object value) {
        return indexOfValue(value) >= 0;
    }

    /**
     * 判断是否包含集合中的全部 key
     * @param collection key 集合
     * @return 全部包含时返回 true
     */
    public boolean containsAll(Collection<?> collection) {
        return MapCollections.containsAllHelper(this, collection);
    }

    /**
     * 根据 key 获取 value
     * @param key key
     * @return key 对应的 value,不存在时返回 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        int index = indexOfKey(key);
        return index >= 0 ? (V) this.array[ContainerHelpers.valueIndex(index)] : null;
    }

    /**
     * 根据 key 获取 value,不存在时返回默认值
     *
     * <p>
     * 相比 {@link Map#getOrDefault(Object, Object)} 默认实现,这里只做一次 key 定位
     * @param key key
     * @param defaultValue 默认值
     * @return key 对应的 value 或默认值
     */
    @Override
    @SuppressWarnings("unchecked")
    public V getOrDefault(Object key, V defaultValue) {
        int index = indexOfKey(key);
        return index >= 0 ? (V) this.array[ContainerHelpers.valueIndex(index)] : defaultValue;
    }

    /**
     * 返回 key 所在下标
     * @param key key
     * @return 存在时返回下标,不存在时返回负数,负数编码方式与 {@link Arrays#binarySearch(int[], int)} 一致
     */
    public int indexOfKey(Object key) {
        return key == null ? indexOfNull() : indexOf(key, key.hashCode());
    }

    /**
     * 返回 value 所在下标
     *
     * <p>
     * value 没有排序信息,因此只能线性扫描; 多个 key 映射到同一 value 时只返回其中一个
     * @param value value
     * @return 存在时返回下标,不存在时返回 -1
     */
    public int indexOfValue(Object value) {
        for (int i = 0; i < this.size; i++) {
            if (ContainerHelpers.equal(value, this.array[ContainerHelpers.valueIndex(i)])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 返回指定位置的 key
     *
     * <p>
     * 下标顺序是内部 hash 排序后的顺序,不是插入顺序
     * @param index Entry 下标,范围为 {@code [0, size()-1]}
     * @return key
     */
    @SuppressWarnings("unchecked")
    public K keyAt(int index) {
        ContainerHelpers.checkIndex(index, this.size);
        return (K) this.array[ContainerHelpers.keyIndex(index)];
    }

    /**
     * 返回指定位置的 value
     *
     * <p>
     * 下标顺序是内部 hash 排序后的顺序,不是插入顺序
     * @param index Entry 下标,范围为 {@code [0, size()-1]}
     * @return value
     */
    @SuppressWarnings("unchecked")
    public V valueAt(int index) {
        ContainerHelpers.checkIndex(index, this.size);
        return (V) this.array[ContainerHelpers.valueIndex(index)];
    }

    /**
     * 替换指定位置的 value
     *
     * <p>
     * 该操作不改变 Entry 数量,不属于结构性修改
     * @param index Entry 下标,范围为 {@code [0, size()-1]}
     * @param value 新 value
     * @return 旧 value
     */
    @SuppressWarnings("unchecked")
    public V setValueAt(int index, V value) {
        ContainerHelpers.checkIndex(index, this.size);
        int valueIndex = ContainerHelpers.valueIndex(index);
        V old = (V) this.array[valueIndex];
        this.array[valueIndex] = value;
        return old;
    }

    /**
     * 新增或替换一个 Entry
     * @param key key
     * @param value value
     * @return key 原本对应的 value,不存在时返回 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        int hash = ContainerHelpers.hash(key);
        int index = key == null ? indexOfNull() : indexOf(key, hash);
        if (index >= 0) {
            int valueIndex = ContainerHelpers.valueIndex(index);
            V old = (V) this.array[valueIndex];
            this.array[valueIndex] = value;
            return old;
        }

        int insertionIndex = ~index;
        int oldSize = this.size;
        ensureCapacityInternal(oldSize + 1);

        if (insertionIndex < oldSize) {
            System.arraycopy(this.hashes, insertionIndex, this.hashes, insertionIndex + 1, oldSize - insertionIndex);
            System.arraycopy(this.array, ContainerHelpers.keyIndex(insertionIndex), this.array,
                    ContainerHelpers.keyIndex(insertionIndex + 1), (oldSize - insertionIndex) * ENTRY_WIDTH);
        }

        this.hashes[insertionIndex] = hash;
        this.array[ContainerHelpers.keyIndex(insertionIndex)] = key;
        this.array[ContainerHelpers.valueIndex(insertionIndex)] = value;
        this.size = oldSize + 1;
        this.modCount++;
        return null;
    }

    /**
     * 追加一个 Entry 的快速路径(无去重校验)
     *
     * <p>
     * 该方法对应 Android 的 {@code append},用于「已知按 key 的 hash 升序批量灌入」的场景当新增 key 的 hash 不小于当前最后一个
     * hash 时,直接追加到末尾,跳过二分定位; 若顺序条件不满足,则退化为 {@link #put(Object, Object)}
     *
     * <p>
     * <b>注意:</b>与 {@link #put(Object, Object)} 不同,本方法在追加分支<b>不做去重检查</b>,因此有可能写入重复 key 批量
     * append 之后可调用 {@link #validate()} 校验是否产生了重复 key与 Android 原版的区别是:这里在容量不足时会自动扩容,
     * 而不是抛出「Array is full」
     * @param key key
     * @param value value
     */
    public void append(K key, V value) {
        int hash = ContainerHelpers.hash(key);
        int oldSize = this.size;
        if (oldSize != 0 && hash < this.hashes[oldSize - 1]) {
            // 追加会破坏 hash 升序,退化为标准 put(put 内部会去重)
            put(key, value);
            return;
        }

        ensureCapacityInternal(oldSize + 1);
        this.hashes[oldSize] = hash;
        this.array[ContainerHelpers.keyIndex(oldSize)] = key;
        this.array[ContainerHelpers.valueIndex(oldSize)] = value;
        this.size = oldSize + 1;
        this.modCount++;
    }

    /**
     * 校验内部数组是否存在重复 key
     *
     * <p>
     * 主要用于 {@link #append(Object, Object)} 批量灌入后的一致性检查发现重复 key 时抛出
     * {@link IllegalArgumentException}
     */
    public void validate() {
        int currentSize = this.size;
        for (int i = 1; i < currentSize; i++) {
            int hash = this.hashes[i];
            if (hash != this.hashes[i - 1]) {
                continue;
            }
            // 进入一段相同 hash 的区间,向前回溯检查是否有相同 key
            Object current = this.array[ContainerHelpers.keyIndex(i)];
            for (int j = i - 1; j >= 0 && this.hashes[j] == hash; j--) {
                Object prev = this.array[ContainerHelpers.keyIndex(j)];
                if (current == prev || (current != null && current.equals(prev))) {
                    throw new IllegalArgumentException("ArrayMap 中存在重复 key: " + current);
                }
            }
        }
    }

    /**
     * 批量写入另一个 ArrayMap
     *
     * <p>
     * 当前 Map 为空时可直接整段复制源数组,避免逐个二分插入
     * @param map 数据来源,不能为 null
     */
    public void putAll(ArrayMap<? extends K, ? extends V> map) {
        Objects.requireNonNull(map, "map 不能为 null");
        if (map == this || map.size == 0) {
            return;
        }

        int mapSize = map.size;
        ensureCapacity(this.size + mapSize);
        if (this.size == 0) {
            System.arraycopy(map.hashes, 0, this.hashes, 0, mapSize);
            System.arraycopy(map.array, 0, this.array, 0, mapSize * ENTRY_WIDTH);
            this.size = mapSize;
            this.modCount++;
            return;
        }

        for (int i = 0; i < mapSize; i++) {
            put(map.keyAt(i), map.valueAt(i));
        }
    }

    /**
     * 批量写入标准 Map
     * @param map 数据来源,不能为 null
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        Objects.requireNonNull(map, "map 不能为 null");
        ensureCapacity(this.size + map.size());
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 根据 key 删除 Entry
     * @param key key
     * @return 被删除的 value,不存在时返回 null
     */
    @Override
    public V remove(Object key) {
        int index = indexOfKey(key);
        return index >= 0 ? removeAt(index) : null;
    }

    /**
     * 当 key 与 value 同时匹配时删除 Entry
     * @param key key
     * @param value value
     * @return 删除成功时返回 true
     */
    @Override
    public boolean remove(Object key, Object value) {
        int index = indexOfKey(key);
        if (index < 0 || !ContainerHelpers.equal(valueAt(index), value)) {
            return false;
        }
        removeAt(index);
        return true;
    }

    /**
     * 删除指定下标的 Entry
     * @param index Entry 下标,范围为 {@code [0, size()-1]}
     * @return 被删除的 value
     */
    @SuppressWarnings("unchecked")
    public V removeAt(int index) {
        ContainerHelpers.checkIndex(index, this.size);

        V oldValue = (V) this.array[ContainerHelpers.valueIndex(index)];
        int oldSize = this.size;
        if (oldSize <= 1) {
            clear();
            return oldValue;
        }

        int newSize = oldSize - 1;
        if (shouldShrink(newSize)) {
            int[] oldHashes = this.hashes;
            Object[] oldArray = this.array;
            int newCapacity = idealCapacityAfterRemove(newSize);
            int[] newHashes = new int[newCapacity];
            Object[] newArray = newObjectArray(newCapacity);

            if (index > 0) {
                System.arraycopy(oldHashes, 0, newHashes, 0, index);
                System.arraycopy(oldArray, 0, newArray, 0, index * ENTRY_WIDTH);
            }
            if (index < newSize) {
                int movedEntries = newSize - index;
                System.arraycopy(oldHashes, index + 1, newHashes, index, movedEntries);
                System.arraycopy(oldArray, ContainerHelpers.keyIndex(index + 1), newArray,
                        ContainerHelpers.keyIndex(index), movedEntries * ENTRY_WIDTH);
            }

            this.hashes = newHashes;
            this.array = newArray;
        } else {
            if (index < newSize) {
                System.arraycopy(this.hashes, index + 1, this.hashes, index, newSize - index);
                System.arraycopy(this.array, ContainerHelpers.keyIndex(index + 1), this.array,
                        ContainerHelpers.keyIndex(index), (newSize - index) * ENTRY_WIDTH);
            }
            // 释放尾部槽位引用,帮助 GC
            this.array[ContainerHelpers.keyIndex(newSize)] = null;
            this.array[ContainerHelpers.valueIndex(newSize)] = null;
        }

        this.size = newSize;
        this.modCount++;
        return oldValue;
    }

    /**
     * 删除集合中出现的全部 key
     * @param collection key 集合
     * @return Map 发生变化时返回 true
     */
    public boolean removeAll(Collection<?> collection) {
        return MapCollections.removeAllHelper(this, collection);
    }

    /**
     * 只保留集合中出现的 key
     * @param collection key 集合
     * @return Map 发生变化时返回 true
     */
    public boolean retainAll(Collection<?> collection) {
        return MapCollections.retainAllHelper(this, collection);
    }

    /**
     * 清空所有 Entry,并释放底层数组
     */
    @Override
    public void clear() {
        if (this.size == 0) {
            return;
        }
        this.hashes = EmptyArray.INT;
        this.array = EmptyArray.OBJECT;
        this.size = 0;
        this.modCount++;
    }

    /**
     * 清空所有 Entry,但保留当前容量(不释放底层数组)
     *
     * <p>
     * 对应 Android 的 {@code erase}适合需要反复清空并重新灌入,希望复用底层数组以减少分配的场景
     */
    public void erase() {
        if (this.size == 0) {
            return;
        }
        Arrays.fill(this.array, 0, this.size * ENTRY_WIDTH, null);
        this.size = 0;
        this.modCount++;
    }

    /**
     * 当 key 不存在或当前 value 为 null 时写入新 value
     * @param key key
     * @param value value
     * @return 旧 value,不存在或旧 value 为 null 时返回 null
     */
    @Override
    public V putIfAbsent(K key, V value) {
        int index = indexOfKey(key);
        if (index < 0) {
            return put(key, value);
        }
        V old = valueAt(index);
        if (old == null) {
            setValueAt(index, value);
        }
        return old;
    }

    /**
     * 当 key 存在时替换 value
     * @param key key
     * @param value 新 value
     * @return 旧 value,不存在时返回 null
     */
    @Override
    public V replace(K key, V value) {
        int index = indexOfKey(key);
        return index >= 0 ? setValueAt(index, value) : null;
    }

    /**
     * 当 key 和 oldValue 同时匹配时替换为 newValue
     * @param key key
     * @param oldValue 期望的旧 value
     * @param newValue 新 value
     * @return 替换成功时返回 true
     */
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        int index = indexOfKey(key);
        if (index < 0 || !ContainerHelpers.equal(valueAt(index), oldValue)) {
            return false;
        }
        setValueAt(index, newValue);
        return true;
    }

    /**
     * 按存储顺序对每个 Entry 执行动作
     *
     * <p>
     * 遍历期间检测到结构性修改时抛出 {@link ConcurrentModificationException}
     * @param action 处理函数,不能为 null
     */
    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action, "action 不能为 null");
        int expectedModCount = this.modCount;
        for (int i = 0; i < this.size; i++) {
            action.accept(keyAt(i), valueAt(i));
            if (this.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * 用函数结果替换所有 value
     *
     * <p>
     * 替换 value 本身不是结构性修改; 如果函数执行过程中改变了 Map 结构,则抛出 {@link ConcurrentModificationException}
     * @param function 替换函数,不能为 null
     */
    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function, "function 不能为 null");
        int expectedModCount = this.modCount;
        for (int i = 0; i < this.size; i++) {
            K key = keyAt(i);
            V oldValue = valueAt(i);
            V newValue = function.apply(key, oldValue);
            if (this.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            setValueAt(i, newValue);
        }
    }

    /**
     * 返回 entrySet 实时视图
     * @return entrySet
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        return getCollection().getEntrySet();
    }

    /**
     * 返回 keySet 实时视图
     * @return keySet
     */
    @Override
    public Set<K> keySet() {
        return getCollection().getKeySet();
    }

    /**
     * 返回 values 实时视图
     * @return values
     */
    @Override
    public Collection<V> values() {
        return getCollection().getValues();
    }

    /**
     * 判断内容是否与另一个 Map 相同
     *
     * <p>
     * 比较规则与 {@link Map#equals(Object)} 契约一致
     * @param object 比较对象
     * @return 内容相同时返回 true
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof Map<?, ?> map)) {
            return false;
        }
        if (this.size != map.size()) {
            return false;
        }
        try {
            for (int i = 0; i < this.size; i++) {
                K key = keyAt(i);
                V mine = valueAt(i);
                Object theirs = map.get(key);
                if (mine == null) {
                    if (theirs != null || !map.containsKey(key)) {
                        return false;
                    }
                } else if (!mine.equals(theirs)) {
                    return false;
                }
            }
            return true;
        } catch (ClassCastException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * 返回与 {@link Map} 契约兼容的 hashCode
     *
     * <p>
     * {@link Map} 契约要求 Map 的 hashCode 等于所有 Entry hashCode 之和,其中每个 Entry 的 hashCode 为
     * {@code keyHash ^ valueHash}由于内部 {@code hashes[i]} 保存的正是 key 的 hashCode(null key 记为
     * 0), 此处直接使用即可
     * @return hashCode
     */
    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < this.size; i++) {
            Object value = this.array[ContainerHelpers.valueIndex(i)];
            result += this.hashes[i] ^ (value == null ? 0 : value.hashCode());
        }
        return result;
    }

    /**
     * 返回 Map 风格的字符串,形如 {@code {k1=v1, k2=v2}}
     *
     * <p>
     * 若 Map 把自身作为 key 或 value,对应位置显示 {@code (this Map)}
     * @return 字符串表示
     */
    @Override
    public String toString() {
        if (isEmpty()) {
            return "{}";
        }

        StringBuilder builder = new StringBuilder(this.size * 28);
        builder.append('{');
        for (int i = 0; i < this.size; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Object key = this.array[ContainerHelpers.keyIndex(i)];
            Object value = this.array[ContainerHelpers.valueIndex(i)];
            builder.append(key == this ? "(this Map)" : key);
            builder.append('=');
            builder.append(value == this ? "(this Map)" : value);
        }
        builder.append('}');
        return builder.toString();
    }

    /**
     * 创建当前 ArrayMap 的浅拷贝
     * @return 新 ArrayMap,key 和 value 对象本身不会被复制
     */
    @Override
    @SuppressWarnings("unchecked")
    public ArrayMap<K, V> clone() {
        try {
            ArrayMap<K, V> clone = (ArrayMap<K, V>) super.clone();
            clone.hashes = this.size == 0 ? EmptyArray.INT : Arrays.copyOf(this.hashes, this.hashes.length);
            clone.array = this.size == 0 ? EmptyArray.OBJECT : Arrays.copyOf(this.array, this.array.length);
            clone.modCount = 0;
            clone.collections = null;
            return clone;
        } catch (CloneNotSupportedException ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * 返回当前结构性修改次数
     * @return 修改次数
     */
    final int getModCount() {
        return this.modCount;
    }

    /**
     * 按 key 和 hash 查找 Entry
     *
     * <p>
     * hash 数组是有序的,先二分定位任意一个相同 hash 的位置,再向前后扫描 hash 冲突区间该方法为包内可见,供视图适配器复用
     * @param key key
     * @param hash key 的 hash
     * @return 存在时返回下标,不存在时返回负数插入点
     */
    int indexOf(Object key, int hash) {
        int currentSize = this.size;
        if (currentSize == 0) {
            return ~0;
        }

        int index = ContainerHelpers.binarySearch(this.hashes, currentSize, hash);
        if (index < 0) {
            return index;
        }
        if (ContainerHelpers.equal(key, this.array[ContainerHelpers.keyIndex(index)])) {
            return index;
        }

        // 向后搜索同 Hash 节点
        int end = index + 1;
        while (end < currentSize && ContainerHelpers.sameHash(this.hashes[end], hash)) {
            if (ContainerHelpers.equal(key, this.array[ContainerHelpers.keyIndex(end)])) {
                return end;
            }
            end++;
        }

        // 向前搜索同 Hash 节点
        for (int i = index - 1; i >= 0 && ContainerHelpers.sameHash(this.hashes[i], hash); i--) {
            if (ContainerHelpers.equal(key, this.array[ContainerHelpers.keyIndex(i)])) {
                return i;
            }
        }

        // 未找到匹配 key 时,插入点必须在整个同 Hash 区间的最末端 `~end`
        return ~end;
    }

    /**
     * 查找 null key
     * @return 存在时返回下标,不存在时返回负数插入点
     */
    int indexOfNull() {
        int currentSize = this.size;
        if (currentSize == 0) {
            return ~0;
        }

        int index = ContainerHelpers.binarySearch(this.hashes, currentSize, 0);
        if (index < 0) {
            return index;
        }
        if (this.array[ContainerHelpers.keyIndex(index)] == null) {
            return index;
        }

        int end = index + 1;
        while (end < currentSize && this.hashes[end] == 0) {
            if (this.array[ContainerHelpers.keyIndex(end)] == null) {
                return end;
            }
            end++;
        }

        for (int i = index - 1; i >= 0 && this.hashes[i] == 0; i--) {
            if (this.array[ContainerHelpers.keyIndex(i)] == null) {
                return i;
            }
        }

        return ~end;
    }

    /**
     * 确保至少有指定容量,容量不足时按 1.5 倍增长策略扩容
     * @param minimumCapacity 最小容量
     */
    private void ensureCapacityInternal(int minimumCapacity) {
        if (this.hashes.length >= minimumCapacity) {
            return;
        }
        int newCapacity = ContainerHelpers.growSize(this.hashes.length);
        if (newCapacity < minimumCapacity) {
            newCapacity = minimumCapacity;
        }
        resizeArrays(newCapacity);
    }

    /**
     * 重新分配内部数组并迁移已有元素
     * @param capacity 新容量
     */
    private void resizeArrays(int capacity) {
        int[] newHashes;
        Object[] newArray;
        if (capacity == 0) {
            newHashes = EmptyArray.INT;
            newArray = EmptyArray.OBJECT;
        } else {
            newHashes = new int[capacity];
            newArray = newObjectArray(capacity);
        }

        if (this.size > 0) {
            System.arraycopy(this.hashes, 0, newHashes, 0, this.size);
            System.arraycopy(this.array, 0, newArray, 0, this.size * ENTRY_WIDTH);
        }
        this.hashes = newHashes;
        this.array = newArray;
    }

    /**
     * 删除后是否应收缩数组
     * @param newSize 删除后的大小
     * @return 需要收缩时返回 true
     */
    private boolean shouldShrink(int newSize) {
        return this.hashes.length > 8 && newSize < this.hashes.length / 3;
    }

    /**
     * 计算删除后的目标容量
     * @param newSize 删除后的大小
     * @return 新容量
     */
    private int idealCapacityAfterRemove(int newSize) {
        if (newSize <= ContainerHelpers.BASE_SIZE) {
            return ContainerHelpers.BASE_SIZE;
        }
        if (newSize <= 8) {
            return 8;
        }
        return newSize + (newSize >> 1);
    }

    /**
     * 获取集合视图适配器,按需创建
     * @return 集合视图适配器
     */
    private MapCollections<K, V> getCollection() {
        if (this.collections == null) {
            this.collections = new MapCollections<>() {

                @Override
                int colGetSize() {
                    return ArrayMap.this.size;
                }

                @Override
                Object colGetEntry(int index, int offset) {
                    return ArrayMap.this.array[ContainerHelpers.keyIndex(index) + offset];
                }

                @Override
                int colIndexOfKey(Object key) {
                    return ArrayMap.this.indexOfKey(key);
                }

                @Override
                int colIndexOfValue(Object value) {
                    return ArrayMap.this.indexOfValue(value);
                }

                @Override
                Map<K, V> colGetMap() {
                    return ArrayMap.this;
                }

                @Override
                void colPut(K key, V value) {
                    ArrayMap.this.put(key, value);
                }

                @Override
                V colSetValue(int index, V value) {
                    return ArrayMap.this.setValueAt(index, value);
                }

                @Override
                void colRemoveAt(int index) {
                    ArrayMap.this.removeAt(index);
                }

                @Override
                void colClear() {
                    ArrayMap.this.clear();
                }

                @Override
                int colGetModCount() {
                    return ArrayMap.this.modCount;
                }
            };
        }
        return this.collections;
    }

    /**
     * 自定义序列化写入逻辑:仅写入实际保存的 key/value 键值对,避免浪费传输空间
     */
    @Serial
    private void writeObject(ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();
        s.writeInt(this.size);
        for (int i = 0; i < this.size; i++) {
            s.writeObject(keyAt(i));
            s.writeObject(valueAt(i));
        }
    }

    /**
     * 自定义反序列化读取逻辑:重新计算 hash 并插入,防止跨 JVM 环境 Hash 冲突导致查询失效
     */
    @Serial
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        s.defaultReadObject();
        int num = s.readInt();
        this.hashes = num == 0 ? EmptyArray.INT : new int[num];
        this.array = num == 0 ? EmptyArray.OBJECT : newObjectArray(num);
        this.size = 0;
        for (int i = 0; i < num; i++) {
            K key = (K) s.readObject();
            V value = (V) s.readObject();
            put(key, value);
        }
    }
}
