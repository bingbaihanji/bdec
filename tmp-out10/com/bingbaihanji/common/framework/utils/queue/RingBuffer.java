package com.bingbaihanji.common.framework.utils.queue;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class RingBuffer<E> extends AbstractQueue {
    private static final int MAXIMUM_CAPACITY = 1073741824;
    private final java.lang.Object[] buffer;
    private final int mask;
    private final int capacity;
    private final java.util.concurrent.locks.ReentrantLock lock;
    private final java.util.concurrent.locks.Condition notFull;
    private final java.util.concurrent.locks.Condition notEmpty;
    private long writeIndex;
    private long readIndex;
    public RingBuffer(int param0) {
        {
            this(var1, 0);
            return;
        }
    }
    public RingBuffer(int param0, boolean param1) {
        if (0 > var1) {
            capacity = RingBuffer.calculateThresholdCapacity(var1);
            mask = capacity - 1;
            buffer = new java.lang.Object[?];
            lock = new java.util.concurrent.locks.ReentrantLock(var2);
            notFull = lock.newCondition();
            notEmpty = lock.newCondition();
            return;
        }
         else {
            throw new java.lang.IllegalArgumentException("Capacity must be positive");
        }
    }
    private static int calculateThresholdCapacity(int param0) {
        if (0 >= (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4) >>> 8 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4) >>> 8) >>> 16)) {
            {
            }
            return 1;
        }
         else {
        }
    }
    public boolean offer(java.lang.Object param0) {
        if (0 < (writeIndex - readIndex == (long) capacity)) {
            var0.enqueue(var1);
            var3 = 1;
            lock.unlock();
            return true;
        }
         else {
            var3 = 0;
            lock.unlock();
            return false;
        }
    }
    public java.lang.Object poll() {
        if (0 < (readIndex == writeIndex)) {
            {
                var2 = var0.dequeue();
                lock.unlock();
                return var0.dequeue();
            }
            {
                lock.unlock();
                throw var3;
            }
        }
         else {
            var2 = null;
            lock.unlock();
            return null;
        }
    }
    public java.lang.Object peek() {
        if (0 < (readIndex == writeIndex)) {
            {
                var2 = (int) (readIndex & (long) mask);
                lock.unlock();
                return (int) (readIndex & (long) mask);
            }
            {
                lock.unlock();
                throw var3;
            }
        }
         else {
            var2 = null;
            lock.unlock();
            return null;
        }
    }
    public void put(java.lang.Object param0) {
        while (true) {
            var2 = lock;
            lock.lockInterruptibly();
        }
        {
            var0.enqueue(var1);
            lock.unlock();
        }
        return;
        {
            lock.unlock();
            throw var3;
        }
    }
    public boolean offer(java.lang.Object param0, long param1, java.util.concurrent.TimeUnit param2) {
        {
            var0 = var0.toNanos(var2);
            var0 = var0.toNanos(var2).lock;
            var0.toNanos(var2).lock.lockInterruptibly();
        }
        {
            var0 = 0;
            0.unlock();
            return false;
        }
        {
            var0.toNanos(var2).lock.enqueue(var1);
            var0 = 1;
            1.unlock();
            return true;
        }
    }
    public java.lang.Object take() {
        while (true) {
            var1 = lock;
            lock.lockInterruptibly();
        }
        {
            var2 = var0.dequeue();
            lock.unlock();
            return var0.dequeue();
        }
        {
            lock.unlock();
            throw var3;
        }
    }
    public java.lang.Object poll(long param0, java.util.concurrent.TimeUnit param1) {
        {
            var0 = var3.toNanos(var1);
            var0 = var3.toNanos(var1).lock;
            var3.toNanos(var1).lock.lockInterruptibly();
        }
        {
            var0 = null;
            null.unlock();
            return null;
        }
        {
            var0 = var3.toNanos(var1).lock.dequeue();
            var3.toNanos(var1).lock.dequeue().unlock();
            return var3.toNanos(var1).lock.dequeue();
        }
    }
    public int drainTo(java.lang.Object[] param0) {
        if (0 != var1.length) {
            {
                var2 = lock;
                lock.lock();
                var3 = (int) (writeIndex - readIndex);
            }
            {
                var0 = 0;
                lock.unlock();
                return 0;
            }
            {
                var0 = Math.min((int) (writeIndex - readIndex), var1.length);
                var0 = 0;
            }
            {
                0.notFull.signalAll();
                var0 = 0;
                lock.unlock();
                return 0;
            }
        }
         else {
            return 0;
        }
    }
    private void enqueue(java.lang.Object param0) {
        {
            writeIndex = writeIndex + 1L;
            notEmpty.signal();
            return;
        }
    }
    private java.lang.Object dequeue() {
        {
            var1 = (int) (readIndex & (long) mask);
            var2 = (int) (readIndex & (long) mask);
            readIndex = readIndex + 1L;
            notFull.signal();
            return (int) (readIndex & (long) mask);
        }
    }
    public int size() {
        {
            var1 = lock;
            lock.lock();
            var2 = (int) (writeIndex - readIndex);
            lock.unlock();
            return (int) (writeIndex - readIndex);
        }
    }
    public boolean isEmpty() {
        if (0 != var0.size()) {
            return true;
        }
         else {
        }
    }
    public int capacity() {
        return capacity;
    }
    public int remainingCapacity() {
        {
            var1 = lock;
            lock.lock();
            var2 = capacity - (int) (writeIndex - readIndex);
            lock.unlock();
            return capacity - (int) (writeIndex - readIndex);
        }
    }
    public void clear() {
        {
            var1 = lock;
            lock.lock();
            Arrays.fill(buffer, null);
            writeIndex = 0L;
            readIndex = 0L;
            notFull.signalAll();
            lock.unlock();
        }
        return;
    }
    public java.util.Iterator iterator() {
        throw new java.lang.UnsupportedOperationException("Iterator directly on thread-safe RingBuffer is not supported");
    }
    public java.lang.String toString() {
        var0.size().makeConcatWithConstants(capacity);
    }
}
