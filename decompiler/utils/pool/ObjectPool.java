package com.bingbaihanji.bdec.decompiler.utils.pool;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 通用对象池
 *
 * <p>
 * 基于 {@link ArrayBlockingQueue} 和
 * {@link Semaphore} 实现 池空时自动创建新对象,超过容量时阻塞
 * </p>
 *
 * <h3>线程安全</h3> 使用 {@link ArrayBlockingQueue} 和
 * {@link Semaphore}, 所有操作线程安全
 *
 * @param <T> 对象类型
 * @author 冰白寒祭
 * @since 2026-07-24
 */
public class ObjectPool<T> {

    // 空闲对象队列
    private final BlockingQueue<T> queue;

    // 信号量(控制并发借出数)
    private final Semaphore semaphore;

    // 对象工厂
    private final Supplier<T> supplier;

    // 已创建对象总数
    private final AtomicInteger created = new AtomicInteger();

    private final AtomicInteger active = new AtomicInteger();

    public ObjectPool(int capacity, Supplier<T> supplier) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity <= 0");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.semaphore = new Semaphore(capacity);
        this.supplier = Objects.requireNonNull(supplier, "supplier == null");
    }

    // 借出一个对象; 池空时会新建
    public T borrowObject() throws InterruptedException {
        semaphore.acquire();
        active.incrementAndGet();
        try {
            T value = queue.poll();
            if (value == null) {
                created.incrementAndGet();
                return supplier.get();
            }
            return value;
        } catch (RuntimeException | Error e) {
            active.decrementAndGet();
            semaphore.release();
            throw e;
        }
    }

    // 归还一个对象
    public boolean returnObject(T object) {
        if (object == null) {
            return false;
        }
        if (!decrementActive()) {
            return false;
        }
        boolean offered = queue.offer(object);
        if (offered) {
            semaphore.release();
        } else {
            active.incrementAndGet();
        }
        return offered;
    }

    /**
     * 清空池内所有空闲对象 
     */
    public void clear() {
        queue.clear();
    }

    /**
     * 当前已借出未归还的对象数估算值
     */
    public int activeCount() {
        return active.get();
    }

    /**
     * 历史上共创建的对象数
     */
    public int totalCreated() {
        return created.get();
    }

    // 当前空闲对象数
    public int idleCount() {
        return queue.size();
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
