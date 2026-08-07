package com.bingbaihanji.bdec.decompiler.utils.pool;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 固定容量对象池
 *
 * <p>
 * 基于 {@link ConcurrentLinkedQueue} 和 CAS 实现 容量固定,池空且已达上限时
 * {@link #borrowObject()} 返回 null 适用于对象创建开销较大且需要控制总量的场景
 * </p>
 *
 * <h3>线程安全</h3> 使用 {@link ConcurrentLinkedQueue} 和
 * {@link AtomicInteger} CAS, 所有操作线程安全
 *
 * @param <T> 对象类型
 * @author 冰白寒祭
 * @since 2026-07-24
 */
public class FixedSizePool<T> {

    // 最大容量
    private final int capacity;

    // 对象工厂
    private final Supplier<T> supplier;

    // 空闲对象队列
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();

    // 已创建对象总数
    private final AtomicInteger created = new AtomicInteger();

    // 当前借出未归还数
    private final AtomicInteger active = new AtomicInteger();

    public FixedSizePool(int capacity, Supplier<T> supplier) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity <= 0");
        }
        this.capacity = capacity;
        this.supplier = Objects.requireNonNull(supplier, "supplier == null");
    }

    // 尝试借出对象 没有空闲且已达上限时返回 null
    public T borrowObject() {
        T value = queue.poll();
        if (value != null) {
            active.incrementAndGet();
            return value;
        }
        while (true) {
            int current = created.get();
            if (current >= capacity) {
                return null;
            }
            if (created.compareAndSet(current, current + 1)) {
                active.incrementAndGet();
                try {
                    return supplier.get();
                } catch (RuntimeException | Error e) {
                    active.decrementAndGet();
                    created.decrementAndGet();
                    throw e;
                }
            }
        }
    }

    // 归还对象到池中
    public boolean returnObject(T object) {
        if (object == null || !decrementActive()) {
            return false;
        }
        return queue.offer(object);
    }

    // 清空空闲对象
    public void clear() {
        int removed = 0;
        while (queue.poll() != null) {
            removed++;
        }
        if (removed > 0) {
            created.addAndGet(-removed);
        }
    }

    // 空闲数量
    public int idleCount() {
        return queue.size();
    }

    // 活跃数量(已借出未归还)
    public int activeCount() {
        return active.get();
    }

    // 剩余容量
    public int remainingCapacity() {
        return Math.max(0, capacity - created.get());
    }

    private boolean decrementActive() {
        while (true) {
            int current = active.get();
            if (current <= 0) {
                return false;
            }
            if (active.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

}
