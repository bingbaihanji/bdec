package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 固定容量 TopK 堆结构
 *
 * <p>
 * 基于 {@link PriorityQueue},内部使用最小堆保存最大的 K 个元素 适用于海量数据中筛选 TopK 的场景
 * </p>
 *
 * <h3>线程安全</h3> 使用 {@link ReentrantLock} 保护所有读写操作,线程安全
 *
 * @param <E> 元素类型
 * @author 冰白寒祭
 * @since 2026-07-24
 */
public class TopKHeap<E> {

    private final int capacity;  // 堆容量(最多保留K个元素)

    private final Comparator<? super E> comparator;  // 比较器(null=自然排序)

    private final PriorityQueue<E> heap;  // 底层最小堆(保留最大的K个)

    private final ReentrantLock lock = new ReentrantLock();  // 可重入锁

    /**
     * 构造 TopK 堆
     *
     * <p>比较器不能为 {@code null} —— 需要比较器来确定元素大小顺序
     * 如果需要自然排序,可使用 {@code Comparator.naturalOrder()}
     * @param capacity 堆容量,即最多保留的元素个数
     * @param comparator 比较器,用于确定元素大小顺序; 不能为 {@code null}
     * @throws IllegalArgumentException 如果 capacity &lt;= 0
     * @throws NullPointerException 如果 comparator 为 null
     */
    public TopKHeap(int capacity, Comparator<? super E> comparator) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity <= 0");
        }
        Objects.requireNonNull(comparator, "comparator 不能为 null");
        this.capacity = capacity;
        this.comparator = comparator;
        this.heap = new PriorityQueue<>(capacity, comparator);
    }

    /**
     * 尝试纳入一个候选元素
     *
     * <p>
     * 如果元素为 {@code null},该方法静默返回 {@code false},不会将 {@code null} 加入堆中
     * </p>
     * @param element 候选元素,可为 {@code null}(静默忽略)
     * @return 如果元素被成功加入堆中返回 {@code true},否则返回 {@code false}
     */
    public boolean add(E element) {
        if (element == null) {
            return false;
        }
        lock.lock();
        try {
            if (heap.size() < capacity) {
                heap.offer(element);
                return true;
            }
            E min = heap.peek();
            if (compare(element, min) <= 0) {
                return false;
            }
            heap.poll();
            heap.offer(element);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回当前 TopK 元素列表,并按从大到小排序
     * @return 从大到小排列的 TopK 元素列表
     */
    public List<E> getTopK() {
        lock.lock();
        try {
            List<E> result = new ArrayList<>(heap);
            result.sort(comparator.reversed());
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 比较两个元素
     * @param left 左元素
     * @param right 右元素
     * @return 负数,零或正数,分别表示 left &lt; ,=,&gt;  right
     */
    private int compare(E left, E right) {
        return comparator.compare(left, right);
    }

    /**
     * 返回当前堆中实际存放的元素数量
     * @return 堆中元素数量
     */
    public int size() {
        lock.lock();
        try {
            return heap.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回堆的容量上限
     * @return 容量上限
     */
    public int capacity() {
        return capacity;
    }

    /**
     * 返回堆顶元素(最小元素),不删除
     * @return 堆顶元素,如果堆为空则返回 {@code null}
     */
    public E peek() {
        lock.lock();
        try {
            return heap.peek();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空堆中所有元素
     */
    public void clear() {
        lock.lock();
        try {
            heap.clear();
        } finally {
            lock.unlock();
        }
    }

}
