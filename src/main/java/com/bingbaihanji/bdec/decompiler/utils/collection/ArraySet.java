package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 面向 Java SE / SpringBoot 的轻量级 ArraySet
 *
 * <p>
 * 本类参考 Android {@code ArraySet} 的数据结构思想,但实现完全基于 Java SE,不依赖 Android,Lombok 或第三方库 与它的姊妹类
 * {@link ArrayMap} 类似,内部使用两个平行数组保存数据:
 * <ul>
 * <li>{@code int[] hashes}:按升序保存元素的 hash</li>
 * <li>{@code Object[] array}:按同样顺序保存元素本身(每个元素占一个槽位,而非像 Map 那样成对存放)</li>
 * </ul>
 *
 * <p>
 * 相比 {@link HashSet},ArraySet 用「二分查找 + 数组移位」换取更少的对象数量和更紧凑的内存布局
 * 它适合中小规模,创建频繁,读多写少的集合,例如权限码集合,标签集合,请求上下文的临时去重集合等场景 元素数量达到数千以上时,{@link HashSet} 仍是更好的选择
 *
 * <p>
 * <b>相较 Android 原版的现代化改造:</b>
 * <ul>
 * <li>移除了全局静态数组缓存({@code sBaseCache} / {@code sTwiceBaseCache} 及其锁) 该缓存是为 Android 受限堆减少
 * GC 而设计的,在 JVM 上属于反模式(锁竞争,内存滞留,线程安全隐患)</li>
 * <li>移除了 {@code identityHashCode} 模式:始终使用 {@link Object#hashCode()} /
 * {@link Object#equals(Object)}, 从而保证 {@link #hashCode()} 满足 {@link Set} 契约</li>
 * <li>移除了 Android {@code Log},{@code UtilConfig} 等平台依赖</li>
 * <li>引入 {@code modCount},迭代器具备 fail-fast 能力</li>
 * <li>采用 1.5 倍扩容(复用 {@link ContainerHelpers}),并在删除后按需收缩数组</li>
 * <li>{@link #toString()} 采用 Java 标准集合的 {@code [a, b, c]} 形式</li>
 * </ul>
 *
 * <p>
 * 本类不是线程安全的并发读写时应在外部加锁,或使用 {@link java.util.concurrent.CopyOnWriteArraySet} 等线程安全容器
 *
 * @param <E> 元素类型
 * @author bingbaihanji
 * @since 1.0
 */
public final class ArraySet<E> implements Set<E>, Cloneable, Serializable {

    @Serial
    private static final long serialVersionUID = 2002072020010307L;

    /**
     * 结构性修改次数,用于支持 fail-fast 迭代器
     *
     * <p>
     * 新增,删除,清空属于结构性修改; 仅遍历,查询不属于结构性修改
     */
    private transient int modCount;

    /**
     * hash 数组,长度表示当前容量,前 {@link #size} 个元素有效,按升序排列
     */
    private int[] hashes;

    /**
     * 元素数组,长度与 {@link #hashes} 一致,前 {@link #size} 个槽位有效,与 hash 一一对应
     */
    private Object[] array;

    /**
     * 当前保存的元素数量
     */
    private int size;

    /**
     * 创建一个空 ArraySet默认容量为 0,首次写入时才分配数组
     */
    public ArraySet() {
        this.hashes = EmptyArray.INT;
        this.array = EmptyArray.OBJECT;
    }

    /**
     * 创建指定初始容量的 ArraySet
     * @param capacity 初始容量,不能小于 0
     */
    public ArraySet(int capacity) {
        ContainerHelpers.checkCapacity(capacity);
        if (capacity == 0) {
            this.hashes = EmptyArray.INT;
            this.array = EmptyArray.OBJECT;
        } else {
            this.hashes = new int[capacity];
            this.array = new Object[capacity];
        }
    }

    /**
     * 使用另一个 ArraySet 的内容创建新实例
     * @param set 数据来源,允许为 null
     */
    public ArraySet(ArraySet<? extends E> set) {
        this(set == null ? 0 : set.size);
        if (set != null) {
            addAll(set);
        }
    }

    /**
     * 使用任意集合的内容创建 ArraySet,自动去重
     * @param collection 数据来源,允许为 null
     */
    public ArraySet(Collection<? extends E> collection) {
        this(collection == null ? 0 : collection.size());
        if (collection != null) {
            addAll(collection);
        }
    }

    /**
     * 使用数组的内容创建 ArraySet,自动去重
     * @param elements 数据来源,允许为 null
     */
    public ArraySet(E[] elements) {
        this(elements == null ? 0 : elements.length);
        if (elements != null) {
            this.addAll(Arrays.asList(elements));
        }
    }

    /**
     * 返回当前元素数量
     * @return 元素数量
     */
    @Override
    public int size() {
        return this.size;
    }

    /**
     * 判断集合是否为空
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
     * 判断集合是否包含指定元素
     * @param value 待查找元素
     * @return 包含时返回 true
     */
    @Override
    public boolean contains(Object value) {
        return indexOf(value) >= 0;
    }

    /**
     * 判断集合是否包含指定集合中的全部元素
     * @param collection 待检查集合
     * @return 全部包含时返回 true
     */
    @Override
    public boolean containsAll(Collection<?> collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        for (Object value : collection) {
            if (!contains(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 返回元素所在下标
     * @param value 待查找元素
     * @return 存在时返回下标,不存在时返回负数,负数编码方式与 {@link Arrays#binarySearch(int[], int)} 一致
     */
    public int indexOf(Object value) {
        return value == null ? indexOfNull() : indexOf(value, value.hashCode());
    }

    /**
     * 返回指定下标处的元素
     *
     * <p>
     * 下标顺序是内部 hash 排序后的顺序,不是插入顺序
     * @param index 元素下标,范围为 {@code [0, size()-1]}
     * @return 该下标处的元素
     */
    @SuppressWarnings("unchecked")
    public E valueAt(int index) {
        ContainerHelpers.checkIndex(index, this.size);
        return (E) this.array[index];
    }

    /**
     * 添加一个元素若集合已包含该元素则不做任何修改
     * @param value 待添加元素,允许为 null
     * @return 集合发生变化(成功添加)时返回 true
     */
    @Override
    public boolean add(E value) {
        int hash = value == null ? 0 : value.hashCode();
        int index = value == null ? indexOfNull() : indexOf(value, hash);
        if (index >= 0) {
            return false;
        }

        int insertionIndex = ~index;
        int oldSize = this.size;
        ensureCapacityInternal(oldSize + 1);

        if (insertionIndex < oldSize) {
            System.arraycopy(this.hashes, insertionIndex, this.hashes, insertionIndex + 1, oldSize - insertionIndex);
            System.arraycopy(this.array, insertionIndex, this.array, insertionIndex + 1, oldSize - insertionIndex);
        }

        this.hashes[insertionIndex] = hash;
        this.array[insertionIndex] = value;
        this.size = oldSize + 1;
        this.modCount++;
        return true;
    }

    /**
     * 追加一个元素的快速路径
     *
     * <p>
     * 当调用方能保证新元素的 hash 不小于当前最后一个元素的 hash 时,该方法可跳过二分查找的插入定位,直接追加到末尾 若顺序条件不满足,会自动退化为
     * {@link #add(Object)}若元素已存在则不做任何修改
     * @param value 待追加元素,允许为 null
     */
    public void append(E value) {
        int hash = value == null ? 0 : value.hashCode();
        int oldSize = this.size;
        if (oldSize != 0 && hash < this.hashes[oldSize - 1]) {
            // 会破坏 hash 升序,退化为标准 add
            add(value);
            return;
        }
        int existingIndex = value == null ? indexOfNull() : indexOf(value, hash);
        if (existingIndex >= 0) {
            // 元素已存在,Set 语义下不重复添加
            return;
        }

        ensureCapacityInternal(oldSize + 1);
        this.hashes[oldSize] = hash;
        this.array[oldSize] = value;
        this.size = oldSize + 1;
        this.modCount++;
    }

    /**
     * 批量添加另一个 ArraySet 的全部元素
     *
     * <p>
     * 当前集合为空时可直接整段复制源数组,避免逐个二分插入
     * @param set 数据来源,不能为 null
     */
    public void addAll(ArraySet<? extends E> set) {
        Objects.requireNonNull(set, "set 不能为 null");
        int n = set.size;
        ensureCapacity(this.size + n);
        if (this.size == 0) {
            if (n > 0) {
                System.arraycopy(set.hashes, 0, this.hashes, 0, n);
                System.arraycopy(set.array, 0, this.array, 0, n);
                this.size = n;
                this.modCount++;
            }
        } else {
            for (int i = 0; i < n; i++) {
                add(set.valueAt(i));
            }
        }
    }

    /**
     * 批量添加任意集合的全部元素,自动去重
     * @param collection 数据来源,不能为 null
     * @return 集合发生变化时返回 true
     */
    @Override
    public boolean addAll(Collection<? extends E> collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        ensureCapacity(this.size + collection.size());
        boolean changed = false;
        for (E value : collection) {
            changed |= add(value);
        }
        return changed;
    }

    /**
     * 确保内部数组至少能容纳指定数量的元素
     *
     * <p>
     * 该方法只改变内部容量,不改变集合内容,因此不会触发 fail-fast
     * @param minimumCapacity 最小容量,不能小于 0
     */
    public void ensureCapacity(int minimumCapacity) {
        ContainerHelpers.checkCapacity(minimumCapacity);
        if (this.hashes.length < minimumCapacity) {
            resize(minimumCapacity);
        }
    }

    /**
     * 删除指定元素
     * @param value 待删除元素
     * @return 集合发生变化(成功删除)时返回 true
     */
    @Override
    public boolean remove(Object value) {
        int index = indexOf(value);
        if (index >= 0) {
            removeAt(index);
            return true;
        }
        return false;
    }

    /**
     * 删除指定下标处的元素
     * @param index 元素下标,范围为 {@code [0, size()-1]}
     * @return 被删除的元素
     */
    @SuppressWarnings("unchecked")
    public E removeAt(int index) {
        ContainerHelpers.checkIndex(index, this.size);

        E old = (E) this.array[index];
        int oldSize = this.size;
        if (oldSize <= 1) {
            clear();
            return old;
        }

        int newSize = oldSize - 1;
        if (shouldShrink(newSize)) {
            int[] oldHashes = this.hashes;
            Object[] oldArray = this.array;
            int newCapacity = idealCapacityAfterRemove(newSize);
            int[] newHashes = new int[newCapacity];
            Object[] newArray = new Object[newCapacity];

            if (index > 0) {
                System.arraycopy(oldHashes, 0, newHashes, 0, index);
                System.arraycopy(oldArray, 0, newArray, 0, index);
            }
            if (index < newSize) {
                System.arraycopy(oldHashes, index + 1, newHashes, index, newSize - index);
                System.arraycopy(oldArray, index + 1, newArray, index, newSize - index);
            }

            this.hashes = newHashes;
            this.array = newArray;
        } else {
            if (index < newSize) {
                System.arraycopy(this.hashes, index + 1, this.hashes, index, newSize - index);
                System.arraycopy(this.array, index + 1, this.array, index, newSize - index);
            }
            // 释放尾部槽位引用,帮助 GC
            this.array[newSize] = null;
        }

        this.size = newSize;
        this.modCount++;
        return old;
    }

    /**
     * 删除另一个 ArraySet 中出现的全部元素
     * @param set 待删除元素来源,不能为 null
     * @return 集合发生变化时返回 true
     */
    public boolean removeAll(ArraySet<? extends E> set) {
        Objects.requireNonNull(set, "set 不能为 null");
        int n = set.size;
        int originalSize = this.size;
        for (int i = 0; i < n; i++) {
            remove(set.valueAt(i));
        }
        return originalSize != this.size;
    }

    /**
     * 删除任意集合中出现的全部元素
     * @param collection 待删除元素来源,不能为 null
     * @return 集合发生变化时返回 true
     */
    @Override
    public boolean removeAll(Collection<?> collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        boolean changed = false;
        for (int i = this.size - 1; i >= 0; i--) {
            if (collection.contains(this.array[i])) {
                removeAt(i);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 只保留指定集合中出现的元素
     * @param collection 保留依据,不能为 null
     * @return 集合发生变化时返回 true
     */
    @Override
    public boolean retainAll(Collection<?> collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        boolean changed = false;
        for (int i = this.size - 1; i >= 0; i--) {
            if (!collection.contains(this.array[i])) {
                removeAt(i);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 删除所有满足条件的元素
     *
     * <p>
     * 采用「原地压缩」策略:一次遍历把保留元素前移,避免逐个删除时的多次数组移位这是比标准 {@link Set#removeIf(Predicate)}
     * 默认迭代器实现更高效的写法
     * @param filter 判定函数,返回 true 表示删除,不能为 null
     * @return 集合发生变化时返回 true
     */
    @Override
    @SuppressWarnings("unchecked")
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter, "filter 不能为 null");
        if (this.size == 0) {
            return false;
        }

        int replaceIndex = 0;
        int numRemoved = 0;
        for (int i = 0; i < this.size; i++) {
            if (filter.test((E) this.array[i])) {
                numRemoved++;
            } else {
                if (replaceIndex != i) {
                    this.array[replaceIndex] = this.array[i];
                    this.hashes[replaceIndex] = this.hashes[i];
                }
                replaceIndex++;
            }
        }

        if (numRemoved == 0) {
            return false;
        }
        if (numRemoved == this.size) {
            clear();
            return true;
        }

        int newSize = this.size - numRemoved;
        if (shouldShrink(newSize)) {
            int newCapacity = idealCapacityAfterRemove(newSize);
            int[] newHashes = new int[newCapacity];
            Object[] newArray = new Object[newCapacity];
            System.arraycopy(this.hashes, 0, newHashes, 0, newSize);
            System.arraycopy(this.array, 0, newArray, 0, newSize);
            this.hashes = newHashes;
            this.array = newArray;
        } else {
            // 释放尾部槽位引用,帮助 GC
            for (int i = newSize; i < this.array.length; i++) {
                this.array[i] = null;
            }
        }

        this.size = newSize;
        this.modCount++;
        return true;
    }

    /**
     * 清空所有元素,并释放底层数组
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
     * 按存储顺序对每个元素执行动作
     *
     * <p>
     * 遍历期间检测到结构性修改时抛出 {@link ConcurrentModificationException}
     * @param action 处理函数,不能为 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action, "action 不能为 null");
        int expectedModCount = this.modCount;
        for (int i = 0; i < this.size; i++) {
            action.accept((E) this.array[i]);
            if (this.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * 返回一个 fail-fast 迭代器,按内部存储顺序遍历元素
     * @return 迭代器
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * 返回包含全部元素的 Object 数组
     * @return 新数组,与集合内部数组相互独立
     */
    @Override
    public Object[] toArray() {
        Object[] result = new Object[this.size];
        System.arraycopy(this.array, 0, result, 0, this.size);
        return result;
    }

    /**
     * 返回包含全部元素的指定类型数组
     *
     * <p>
     * 若传入数组容量足够则复用之,否则新建相同组件类型的数组按 {@link Collection#toArray(Object[])} 约定,
     * 当数组长度大于元素数量时,会在末尾多余位置写入 null
     * @param dest 目标数组
     * @param <T> 数组元素类型
     * @return 存放了全部元素的数组
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] dest) {
        Objects.requireNonNull(dest, "dest 不能为 null");
        T[] result = dest;
        if (result.length < this.size) {
            result = (T[]) Array.newInstance(result.getClass().getComponentType(), this.size);
        }
        System.arraycopy(this.array, 0, result, 0, this.size);
        if (result.length > this.size) {
            result[this.size] = null;
        }
        return result;
    }

    /**
     * 判断内容是否与另一个集合相同
     *
     * <p>
     * 比较规则与 {@link Set#equals(Object)} 契约一致:对象也是 {@link Set},元素数量相同,且互相包含
     * @param object 比较对象
     * @return 内容相同时返回 true
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof Set<?> other)) {
            return false;
        }
        if (this.size != other.size()) {
            return false;
        }
        try {
            for (int i = 0; i < this.size; i++) {
                if (!other.contains(this.array[i])) {
                    return false;
                }
            }
            return true;
        } catch (ClassCastException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * 返回与 {@link Set} 契约兼容的 hashCode
     *
     * <p>
     * {@link Set} 契约要求集合的 hashCode 等于所有元素 hashCode 之和(null 元素贡献 0)由于内部 {@code hashes}
     * 数组保存的正是各元素的 hashCode(null 记为 0),此处直接累加即可
     * @return hashCode
     */
    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < this.size; i++) {
            result += this.hashes[i];
        }
        return result;
    }

    /**
     * 返回集合风格的字符串,形如 {@code [a, b, c]}
     *
     * <p>
     * 若集合把自身作为元素,对应位置显示 {@code (this Collection)}
     * @return 字符串表示
     */
    @Override
    public String toString() {
        if (isEmpty()) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder(this.size * 16);
        builder.append('[');
        for (int i = 0; i < this.size; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Object value = this.array[i];
            builder.append(value == this ? "(this Collection)" : value);
        }
        builder.append(']');
        return builder.toString();
    }

    /**
     * 创建当前 ArraySet 的浅拷贝
     * @return 新 ArraySet,元素对象本身不会被复制
     */
    @Override
    @SuppressWarnings("unchecked")
    public ArraySet<E> clone() {
        try {
            ArraySet<E> clone = (ArraySet<E>) super.clone();
            clone.hashes = this.size == 0 ? EmptyArray.INT : Arrays.copyOf(this.hashes, this.hashes.length);
            clone.array = this.size == 0 ? EmptyArray.OBJECT : Arrays.copyOf(this.array, this.array.length);
            clone.modCount = 0;
            return clone;
        } catch (CloneNotSupportedException ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * 按元素和 hash 查找下标
     *
     * <p>
     * hash 数组是有序的,先二分定位任意一个相同 hash 的位置,再向前后扫描 hash 冲突区间
     * @param value 元素
     * @param hash 元素的 hash
     * @return 存在时返回下标,不存在时返回负数插入点
     */
    private int indexOf(Object value, int hash) {
        int currentSize = this.size;
        if (currentSize == 0) {
            return ~0;
        }

        int index = ContainerHelpers.binarySearch(this.hashes, currentSize, hash);
        if (index < 0) {
            return index;
        }
        if (ContainerHelpers.equal(value, this.array[index])) {
            return index;
        }

        int end = index + 1;
        while (end < currentSize && ContainerHelpers.sameHash(this.hashes[end], hash)) {
            if (ContainerHelpers.equal(value, this.array[end])) {
                return end;
            }
            end++;
        }

        for (int i = index - 1; i >= 0 && ContainerHelpers.sameHash(this.hashes[i], hash); i--) {
            if (ContainerHelpers.equal(value, this.array[i])) {
                return i;
            }
        }

        // 未找到,返回冲突链末尾作为插入点,以减少后续插入时需要移动的元素数量
        return ~end;
    }

    /**
     * 查找 null 元素的下标
     * @return 存在时返回下标,不存在时返回负数插入点
     */
    private int indexOfNull() {
        int currentSize = this.size;
        if (currentSize == 0) {
            return ~0;
        }

        int index = ContainerHelpers.binarySearch(this.hashes, currentSize, 0);
        if (index < 0) {
            return index;
        }
        if (this.array[index] == null) {
            return index;
        }

        int end = index + 1;
        while (end < currentSize && this.hashes[end] == 0) {
            if (this.array[end] == null) {
                return end;
            }
            end++;
        }

        for (int i = index - 1; i >= 0 && this.hashes[i] == 0; i--) {
            if (this.array[i] == null) {
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
        resize(newCapacity);
    }

    /**
     * 重新分配内部数组并迁移已有元素
     * @param capacity 新容量
     */
    private void resize(int capacity) {
        int[] newHashes;
        Object[] newArray;
        if (capacity == 0) {
            newHashes = EmptyArray.INT;
            newArray = EmptyArray.OBJECT;
        } else {
            newHashes = new int[capacity];
            newArray = new Object[capacity];
        }

        if (this.size > 0) {
            System.arraycopy(this.hashes, 0, newHashes, 0, this.size);
            System.arraycopy(this.array, 0, newArray, 0, this.size);
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
     * 自定义序列化写入逻辑
     */
    @Serial
    private void writeObject(ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();
        s.writeInt(this.size);
        for (int i = 0; i < this.size; i++) {
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
        this.array = num == 0 ? EmptyArray.OBJECT : new Object[num];
        this.size = 0;
        for (int i = 0; i < num; i++) {
            E element = (E) s.readObject();
            add(element);
        }
    }


    /**
     * fail-fast 迭代器
     *
     * <p>
     * 迭代器创建时记录 {@code modCount},遍历过程中如检测到集合被迭代器自身以外的方式修改,则抛出
     * {@link ConcurrentModificationException}fail-fast 是错误探测机制,不是并发控制机制
     */
    private final class Itr implements Iterator<E> {

        /**
         * 下一个待返回元素的下标
         */
        private int nextIndex;

        /**
         * 最近一次 {@link #next()} 返回元素的下标,-1 表示尚未返回或已被删除
         */
        private int lastIndex = -1;

        /**
         * 期望的结构性修改次数
         */
        private int expectedModCount = ArraySet.this.modCount;

        @Override
        public boolean hasNext() {
            return this.nextIndex < ArraySet.this.size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E next() {
            checkForComodification();
            if (this.nextIndex >= ArraySet.this.size) {
                throw new NoSuchElementException();
            }
            this.lastIndex = this.nextIndex;
            return (E) ArraySet.this.array[this.nextIndex++];
        }

        @Override
        public void remove() {
            if (this.lastIndex < 0) {
                throw new IllegalStateException("必须先调用 next(),且每次 next() 后只能 remove() 一次");
            }
            checkForComodification();

            ArraySet.this.removeAt(this.lastIndex);
            this.nextIndex = this.lastIndex;
            this.lastIndex = -1;
            this.expectedModCount = ArraySet.this.modCount;
        }

        private void checkForComodification() {
            if (ArraySet.this.modCount != this.expectedModCount) {
                throw new ConcurrentModificationException();
            }
        }

    }

}
