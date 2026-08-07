package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 一个线程安全,支持上限约束的可扩容对象栈(LIFO) 
 *
 * <p>特点: </p>
 * <ul>
 *     <li>基于动态数组实现,无指针开销,GC 友好</li>
 *     <li>自动扩容(2 倍增长),支持设定最大上限 limit</li>
 *     <li>不主动收缩容量,适合对象池与高频复用场景</li>
 *     <li>独占锁实现,无 CAS 失败重试开销,在中低并发下表现极其稳定</li>
 *     <li>支持 {@link Iterator}(快照迭代器),实现 {@link Serializable}</li>
 * </ul>
 *
 * @param <E> 元素类型
 * @author your-team
 * @since 1.0
 */
public class SynchronizedStack<E> implements Iterable<E>, Serializable {

    /**
     * 默认初始容量
     */
    public static final int DEFAULT_CAPACITY = 128;

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * VM 要求的最大数组长度(扣除 8 个 Header 字节,防止某些 JVM 报 OOM)
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 允许存储的最大元素数量上限
     */
    private final int limit;

    /**
     * 内部存储数组
     */
    private transient Object[] elements;

    /**
     * 当前栈内元素数量(也是下一个入栈元素的索引位置)
     */
    private int size;

    /**
     * 创建无严格容量上限的栈(上限为 JVM 允许的最大数组容量) 
     */
    public SynchronizedStack() {
        this(DEFAULT_CAPACITY, MAX_ARRAY_SIZE);
    }

    /**
     * 创建指定初始容量及上限的栈 
     *
     * @param initialCapacity 初始容量
     * @param limit           最大容量限制
     */
    public SynchronizedStack(int initialCapacity, int limit) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be > 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }

        // 规范 limit 上限,防止超出 JVM 数组极限
        this.limit = Math.min(limit, MAX_ARRAY_SIZE);
        int cap = Math.min(initialCapacity, this.limit);

        this.elements = new Object[cap];
        this.size = 0;
    }

    /**
     * 压栈(Push) 
     *
     * @param element 待压入元素,不能为空
     * @return true 成功;false 已达到最大容量限制
     */
    public synchronized boolean push(E element) {
        Objects.requireNonNull(element, "element cannot be null");

        if (size == elements.length) {
            if (!expand()) {
                return false;
            }
        }

        elements[size++] = element;
        return true;
    }

    /**
     * 弹出栈顶元素(Pop) 
     *
     * @return 栈顶元素;若栈为空返回 null
     */
    @SuppressWarnings("unchecked")
    public synchronized E pop() {
        if (size == 0) {
            return null;
        }

        int index = --size;
        Object value = elements[index];
        elements[index] = null; // 及时清空引用,避免内存泄漏
        return (E) value;
    }

    /**
     * 查看栈顶元素但不弹出(Peek) 
     *
     * @return 栈顶元素;若栈为空返回 null
     */
    @SuppressWarnings("unchecked")
    public synchronized E peek() {
        if (size == 0) {
            return null;
        }
        return (E) elements[size - 1];
    }

    /**
     * 判断栈是否为空 
     */
    public synchronized boolean isEmpty() {
        return size == 0;
    }

    /**
     * 判断栈元素是否已达到设定的容量上限 limit 
     */
    public synchronized boolean isFull() {
        return size >= limit;
    }

    /**
     * 获取当前存储的元素数量 
     */
    public synchronized int size() {
        return size;
    }

    /**
     * 获取内部数组当前的真实底层容量 
     */
    public synchronized int capacity() {
        return elements.length;
    }

    /**
     * 获取设定的最大容量限制 
     */
    public int limit() {
        return limit;
    }

    /**
     * 判断栈是否包含指定元素(基于 {@link Object#equals}) 
     *
     * @param o 待查找元素,可为 null
     * @return 若包含返回 {@code true}
     */
    public synchronized boolean contains(Object o) {
        if (size == 0) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (Objects.equals(o, elements[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回栈顶元素到栈底元素的数组副本(顺序为 pop 顺序) 
     *
     * @return 包含所有元素的新数组
     */
    public synchronized Object[] toArray() {
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) {
            result[i] = elements[size - 1 - i];
        }
        return result;
    }

    /**
     * 清空栈内所有元素,帮助 GC 回收 
     */
    public synchronized void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(elements, 0, size, null);
        size = 0;
    }

    /**
     * 扩容逻辑: 翻倍增长,但受限于 limit 和 MAX_ARRAY_SIZE 
     *
     * @return true 扩容成功;false 已无法再扩容
     */
    private boolean expand() {
        int currentCapacity = elements.length;
        if (currentCapacity >= limit) {
            return false;
        }

        // 防止溢出的 2 倍扩容
        int newCapacity = currentCapacity << 1;
        if (newCapacity < 0 || newCapacity > limit) {
            newCapacity = limit;
        }

        if (newCapacity <= currentCapacity) {
            return false;
        }

        elements = Arrays.copyOf(elements, newCapacity);
        return true;
    }

    // ======================== 迭代器 ========================

    /**
     * 返回快照迭代器,从栈顶向栈底遍历(pop 顺序) 
     * 迭代器创建时复制当前元素快照,后续修改不影响迭代器 
     *
     * @return 迭代器
     */
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

    // ======================== equals / hashCode ========================

    @Override
    public synchronized boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SynchronizedStack<?> other)) {
            return false;
        }
        if (this.size != other.size) {
            return false;
        }
        // 栈顶到栈底顺序比较
        for (int i = 0; i < size; i++) {
            if (!Objects.equals(this.elements[size - 1 - i], other.elements[other.size - 1 - i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized int hashCode() {
        int result = 1;
        for (int i = 0; i < size; i++) {
            Object e = elements[size - 1 - i];
            result = 31 * result + (e == null ? 0 : e.hashCode());
        }
        return result;
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = size - 1; i >= 0; i--) {
            if (i < size - 1) {
                sb.append(", ");
            }
            sb.append(elements[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
