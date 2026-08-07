package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * 基于 {@code synchronized} 的线程安全无界循环队列 
 *
 * <p>核心特性: </p>
 * <ul>
 *   <li>底层采用循环数组,容量始终为 2 的幂,使用位运算替代取模,性能优异</li>
 *   <li>自动扩容(翻倍),支持手动预分配和收缩容量</li>
 *   <li>所有公开方法均使用 {@code synchronized} 修饰,保证线程安全</li>
 *   <li>不允许存储 {@code null} 元素</li>
 *   <li>支持 {@link java.util.Queue} 接口所有标准方法,并额外提供容量管理工具</li>
 *   <li>迭代器基于快照(创建时复制),不会抛出 {@code ConcurrentModificationException},但不支持 {@code remove()}</li>
 *   <li>实现 {@link Serializable},{@link Cloneable},支持序列化和克隆</li>
 *   <li>提供 {@link Spliterator} 以支持并行流操作</li>
 *   <li>改进的 {@code removeAt} 采用双向移动策略,平均搬运量减少约 50%</li>
 * </ul>
 *
 * <p><b>使用限制与注意事项: </b></p>
 * <ul>
 *   <li>非阻塞队列,无 {@code take/put/超时等待} 等阻塞方法</li>
 *   <li>全局独占锁,适合中低并发场景(QPS 万级以下);高并发场景建议使用 {@code ConcurrentLinkedQueue} 或 {@code LinkedBlockingQueue}</li>
 *   <li>扩容时需复制全部元素,建议根据业务量预估初始容量,减少运行时扩容</li>
 *   <li>最大容量为 {@code 1 << 30},超出会抛出 {@code IllegalStateException}</li>
 * </ul>
 *
 * <p><b>适用场景: </b></p>
 * <ul>
 *   <li>对象池,线程池任务缓冲,消息缓存</li>
 *   <li>需要标准 {@code Queue} 接口且对 GC 友好的场景</li>
 *   <li>对内存占用不敏感,且元素数量相对稳定的场景</li>
 * </ul>
 *
 * @param <E> 队列中元素的类型
 * @author your-team
 * @since 1.0
 */
public class SynchronizedQueue<E> extends AbstractQueue<E> implements Serializable, Cloneable {

    /**
     * 默认初始容量(必须为 2 的幂)
     */
    public static final int DEFAULT_CAPACITY = 128;

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 最大容量限制(1 << 30),防止数组过大导致内存溢出
     */
    private static final int MAX_CAPACITY = 1 << 30;

    /**
     * 存储元素的数组,长度始终为 2 的幂
     */
    private transient Object[] elements;

    /**
     * 当前数组容量(即 {@code elements.length}),序列化时由 readObject 重新计算
     */
    private transient int capacity;

    /**
     * 位运算掩码,等于 {@code capacity - 1},用于将指针转换为数组下标
     */
    private transient int mask;

    /**
     * 队头指针(指向下一个要出队的元素位置)
     */
    private int head;

    /**
     * 队尾指针(指向下一个要入队的位置)
     */
    private int tail;

    /**
     * 当前队列中有效元素个数
     */
    private int size;

    /**
     * 使用默认容量({@link #DEFAULT_CAPACITY})创建队列
     */
    public SynchronizedQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 使用指定的初始容量创建队列 实际容量会被调整为不小于指定值的 2 的幂 
     *
     * @param initialCapacity 期望的初始容量,必须大于 0
     * @throws IllegalArgumentException 如果 {@code initialCapacity <= 0}
     */
    public SynchronizedQueue(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("capacity must > 0");
        }
        this.capacity = tableSizeFor(initialCapacity);
        this.mask = this.capacity - 1;
        this.elements = new Object[this.capacity];
    }

    // -------------------------- 核心 Queue 方法 --------------------------

    /**
     * 计算大于等于给定值的最小 2 的幂(算法来自 HashMap)
     * 增强边界处理,当 {@code cap <= 1} 时直接返回 1
     *
     * @param cap 期望容量
     * @return 2 的幂次值,介于 1 和 {@link #MAX_CAPACITY} 之间
     */
    private static int tableSizeFor(int cap) {

        if (cap <= 1) {
            return 1;
        }

        int n = cap - 1;

        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;

        if (n >= MAX_CAPACITY) {
            return MAX_CAPACITY;
        }

        return n + 1;
    }

    /**
     * 将指定元素插入队列尾部 队列会自动扩容,除非已达到最大容量
     *
     * @param e 要添加的元素,不能为 {@code null}
     * @return 总是返回 {@code true}
     * @throws NullPointerException 如果元素为 {@code null}
     * @throws IllegalStateException 如果队列已达到最大容量且无法继续扩容
     */
    @Override
    public synchronized boolean offer(E e) {
        Objects.requireNonNull(e);
        if (size == capacity) {
            expand();
        }
        elements[tail] = e;
        tail = (tail + 1) & mask;
        size++;
        return true;
    }

    /**
     * 获取并移除队头元素 如果队列为空,返回 {@code null}
     *
     * @return 队头元素,或 {@code null} 若队列为空
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized E poll() {
        if (size == 0) {
            return null;
        }
        Object value = elements[head];
        elements[head] = null;
        head = (head + 1) & mask;
        size--;
        return (E) value;
    }

    // -------------------------- 查询与状态方法 --------------------------

    /**
     * 获取队头元素但不移除 如果队列为空,返回 {@code null}
     *
     * @return 队头元素,或 {@code null} 若队列为空
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized E peek() {
        if (size == 0) {
            return null;
        }
        return (E) elements[head];
    }

    @Override
    public synchronized boolean isEmpty() {
        return size == 0;
    }

    @Override
    public synchronized int size() {
        return size;
    }

    /**
     * 返回当前底层数组的容量(即最大可容纳元素个数,不触发扩容)
     *
     * @return 当前容量
     */
    public synchronized int currentCapacity() {
        return capacity;
    }

    // -------------------------- 容量管理 --------------------------

    /**
     * 返回队列是否已满(即 {@code size == capacity})
     *
     * @return {@code true} 如果队列已满
     */
    public synchronized boolean isFull() {
        return size == capacity;
    }

    /**
     * 确保队列至少能容纳指定数量的元素(不触发自动扩容)
     * 如果当前容量小于 {@code minCapacity},则扩容至不小于该值的最小 2 的幂
     *
     * @param minCapacity 期望的最小容量
     * @throws IllegalStateException 如果所需容量超过 {@link #MAX_CAPACITY}
     */
    public synchronized void ensureCapacity(int minCapacity) {
        if (minCapacity <= capacity) {
            return;
        }
        if (minCapacity > MAX_CAPACITY) {
            throw new IllegalStateException("Required capacity exceeds maximum: " + MAX_CAPACITY);
        }
        int newCapacity = tableSizeFor(minCapacity);
        if (newCapacity > capacity) {
            resizeArray(newCapacity);
        }
    }

    /**
     * 收缩容量至与当前元素数量相等的最近 2 的幂(最小为 1)
     * 如果当前容量已经小于或等于元素个数所需容量,则不操作
     * 注意: 缩容会触发数组复制,且释放多余内存
     */
    public synchronized void trimToSize() {
        int target = (size == 0) ? 1 : tableSizeFor(size);
        if (target < capacity) {
            resizeArray(target);
        }
    }

    // -------------------------- 包含与删除 --------------------------

    /**
     * 清空队列,将所有元素置为 {@code null},重置头尾指针
     */
    @Override
    public synchronized void clear() {
        if (size == 0) {
            return;
        }
        if (size == capacity) {
            Arrays.fill(elements, null);
        } else if (head < tail) {
            Arrays.fill(elements, head, tail, null);
        } else {
            Arrays.fill(elements, head, capacity, null);
            Arrays.fill(elements, 0, tail, null);
        }
        head = 0;
        tail = 0;
        size = 0;
    }

    @Override
    public synchronized boolean contains(Object o) {
        if (o == null || size == 0) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            int idx = (head + i) & mask;
            if (o.equals(elements[idx])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean remove(Object o) {
        if (o == null || size == 0) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            int idx = (head + i) & mask;
            if (o.equals(elements[idx])) {
                removeAt(idx);
                return true;
            }
        }
        return false;
    }

    // -------------------------- 迭代器与批量操作 --------------------------

    /**
     * 删除指定下标的元素,采用双向移动策略(参考 ArrayDeque)
     * 比较该位置到 head 和到 tail 的距离,选择移动较少的一侧,减少平均搬运量
     *
     * @param removeIndex 要删除的数组下标(有效位置)
     */
    private void removeAt(int removeIndex) {
        assert elements[removeIndex] != null;
        int distanceToHead = (removeIndex - head) & mask;
        int distanceToTail = (tail - removeIndex) & mask;
        if (distanceToHead < distanceToTail) {
            for (int i = removeIndex; i != head; ) {
                int prev = (i - 1) & mask;
                elements[i] = elements[prev];
                i = prev;
            }
            elements[head] = null;
            head = (head + 1) & mask;

        } else {
            int last = (tail - 1) & mask;
            for (int i = removeIndex; i != last; ) {
                int next = (i + 1) & mask;
                elements[i] = elements[next];
                i = next;
            }
            elements[last] = null;
            tail = last;
        }
        size--;
    }

    @Override
    public synchronized Iterator<E> iterator() {
        Object[] snapshot = toArray();
        return new Iterator<>() {

            private int cursor = 0;

            @Override
            public boolean hasNext() {
                return cursor < snapshot.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return (E) snapshot[cursor++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Snapshot iterator does not support remove");
            }
        };
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        Object[] snapshot = toArray(); // toArray() 已同步
        for (Object e : snapshot) {
            @SuppressWarnings("unchecked")
            E element = (E) e;
            action.accept(element);
        }
    }

    // -------------------------- 数组转换 --------------------------

    @Override
    public Spliterator<E> spliterator() {
        Object[] snapshot = toArray(); // toArray() 已同步
        return Spliterators.spliterator(
                snapshot,
                Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.SIZED | Spliterator.SUBSIZED
        );
    }

    @Override
    public synchronized Object[] toArray() {
        Object[] arr = new Object[size];
        copyToArray(arr);
        return arr;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T[] toArray(T[] a) {
        if (a.length < size) {
            a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
        }
        copyToArray(a);
        if (a.length > size) {
            a[size] = null;
        }
        return a;
    }

    // -------------------------- 内部扩容与工具方法 --------------------------

    /**
     * 将队列中的有效元素复制到目标数组(从下标 0 开始)
     *
     * @param dest 目标数组,长度至少为 {@code size}
     */
    private void copyToArray(Object[] dest) {
        if (size == 0) {
            return;
        }
        if (head < tail) {
            System.arraycopy(elements, head, dest, 0, size);
        } else {
            int rightLen = capacity - head;
            System.arraycopy(elements, head, dest, 0, rightLen);
            System.arraycopy(elements, 0, dest, rightLen, tail);
        }
    }

    /**
     * 扩容操作,将容量翻倍 仅在 {@code size == capacity} 时调用
     * 由于队列已满,此时 {@code head == tail},但此方法通用,实际通过 resizeArray 实现
     */
    private void expand() {
        if (capacity >= MAX_CAPACITY) {
            throw new IllegalStateException("Queue has reached maximum capacity: " + MAX_CAPACITY);
        }
        resizeArray(capacity << 1);
    }

    /**
     * 将底层数组替换为新容量(必须为 2 的幂),并重新平铺数据
     * 该方法是通用的,适用于任何 {@code size <= capacity} 的情况
     *
     * @param newCapacity 新容量(2 的幂)
     * @throws IllegalStateException 如果新容量超过 {@link #MAX_CAPACITY}
     */
    private void resizeArray(int newCapacity) {
        if (newCapacity > MAX_CAPACITY) {
            throw new IllegalStateException("New capacity exceeds maximum: " + MAX_CAPACITY);
        }
        if (newCapacity == capacity) {
            return;
        }
        Object[] newArray = new Object[newCapacity];
        int rightLen = Math.min(size, capacity - head);
        System.arraycopy(elements, head, newArray, 0, rightLen);
        int leftLen = size - rightLen;
        if (leftLen > 0) {
            System.arraycopy(elements, 0, newArray, rightLen, leftLen);
        }
        elements = newArray;
        capacity = newCapacity;
        mask = newCapacity - 1;
        head = 0;
        tail = size;
    }

    // -------------------------- Object 方法重写 --------------------------

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof SynchronizedQueue<?> other)) {
            return false;
        }
        return Arrays.equals(this.toArray(), other.toArray());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toArray());
    }

    @Override
    public synchronized String toString() {
        if (size == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int idx = (head + i) & mask;
            sb.append(elements[idx]);
        }
        sb.append(']');
        return sb.toString();
    }

    // -------------------------- Clone 与序列化 --------------------------

    @Override
    @SuppressWarnings("unchecked")
    public synchronized SynchronizedQueue<E> clone() {

        try {

            SynchronizedQueue<E> clone = (SynchronizedQueue<E>) super.clone();
            clone.elements = new Object[this.capacity];
            for (int i = 0; i < this.size; i++) {
                clone.elements[i] = this.elements[(this.head + i) & this.mask];
            }
            clone.head = 0;
            clone.tail = this.size;
            clone.size = this.size;
            clone.capacity = this.capacity;
            clone.mask = this.mask;
            return clone;

        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * 序列化: 只保存元素数量及顺序元素,不保存内部指针布局 
     * 恢复时重建为紧凑布局(head=0, tail=size),与 ArrayDeque 策略一致 
     * 方法已同步,保证并发修改时读到一致状态 
     */
    private synchronized void writeObject(ObjectOutputStream out)
            throws IOException {

        out.writeInt(size);

        for (int i = 0; i < size; i++) {

            int index = (head + i) & mask;

            out.writeObject(elements[index]);
        }
    }

    @Serial
    private void readObject(ObjectInputStream in)
            throws IOException, ClassNotFoundException {

        int size = in.readInt();

        if (size < 0 || size > MAX_CAPACITY) {
            throw new InvalidObjectException(
                    "Invalid queue size: " + size
            );
        }

        int newCapacity = tableSizeFor(
                Math.max(size, DEFAULT_CAPACITY)
        );

        this.capacity = newCapacity;
        this.mask = newCapacity - 1;
        this.elements = new Object[newCapacity];

        this.head = 0;
        this.tail = size;
        this.size = size;


        for (int i = 0; i < size; i++) {
            elements[i] = in.readObject();
        }
    }
}