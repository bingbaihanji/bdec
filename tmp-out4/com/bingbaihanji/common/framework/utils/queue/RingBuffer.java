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
            this(0);
            return;
        }
    }
    public RingBuffer(int param0, boolean param1) {
        {
            super();
            0 > var1;
            <init>();
            new java.lang.Object();
        }
    }
    private static int calculateThresholdCapacity(int param0) {
        {
            var1 = var0 - 1;
            var1 = var0 - 1 | var0 - 1 >>> 1;
            var1 = var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2;
            var1 = var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4;
            var1 = var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4) >>> 8;
            var1 = var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4) >>> 8 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4) >>> 8) >>> 16;
            0 >= (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4) >>> 8 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2 | (var0 - 1 | var0 - 1 >>> 1 | (var0 - 1 | var0 - 1 >>> 1) >>> 2) >>> 4) >>> 8) >>> 16);
        }
        return 1;
    }
    public boolean offer(java.lang.Object param0) {
        {
            var2 = var0.lock;
            lock();
            0 < (var0.writeIndex - var0.readIndex == (long) var0.capacity);
            var3 = 0;
            unlock();
            return false;
        }
    }
    public java.lang.Object poll() {
        {
            var1 = var0.lock;
            lock();
            0 < (var0.readIndex == var0.writeIndex);
            var2 = null;
            unlock();
            return null;
        }
    }
    public java.lang.Object peek() {
        {
            var1 = var0.lock;
            lock();
            0 < (var0.readIndex == var0.writeIndex);
            var2 = null;
            unlock();
            return null;
        }
    }
    public void put(java.lang.Object param0) {
        {
            var2 = var0.lock;
            lockInterruptibly();
        }
        try {
            unlock();
            var3;
        }
         catch (Throwable e) {
            /* handler */;
        }
    }
    public boolean offer(java.lang.Object param0, long param1, java.util.concurrent.TimeUnit param2) {
        {
            var0 = toNanos(var2);
            var0 = toNanos(var2).lock;
            lockInterruptibly();
            0 < (toNanos(var2).lock.writeIndex - toNanos(var2).lock.readIndex == (long) toNanos(var2).lock.capacity);
            0 > (toNanos(var2).lock == 0L);
            var0 = 0;
            unlock();
            return false;
        }
    }
    public java.lang.Object take() {
        {
            var1 = var0.lock;
            lockInterruptibly();
        }
        try {
            unlock();
            var3;
        }
         catch (Throwable e) {
            /* handler */;
        }
    }
    public java.lang.Object poll(long param0, java.util.concurrent.TimeUnit param1) {
        {
            var0 = toNanos(var1);
            var0 = toNanos(var1).lock;
            lockInterruptibly();
            0 < (toNanos(var1).lock.readIndex == toNanos(var1).lock.writeIndex);
            0 > (toNanos(var1).lock == 0L);
            var0 = null;
            unlock();
            return null;
        }
    }
    public int drainTo(java.lang.Object[] param0) {
        {
            0 != var1.length;
            return 0;
        }
    }
    private void enqueue(java.lang.Object param0) {
        {
            var0.writeIndex = var0.writeIndex + 1L;
            signal();
            return;
        }
    }
    private java.lang.Object dequeue() {
        {
            var1 = (int) (var0.readIndex & (long) var0.mask);
            var2 = (int) (var0.readIndex & (long) var0.mask);
            var0.readIndex = var0.readIndex + 1L;
            signal();
            return (int) (var0.readIndex & (long) var0.mask);
        }
    }
    public int size() {
        {
            var1 = var0.lock;
            lock();
            var2 = (int) (var0.writeIndex - var0.readIndex);
            unlock();
            return (int) (var0.writeIndex - var0.readIndex);
        }
    }
    public boolean isEmpty() {
        0 != size();
        return true;
    }
    public int capacity() {
        return var0.capacity;
    }
    public int remainingCapacity() {
        {
            var1 = var0.lock;
            lock();
            var2 = var0.capacity - (int) (var0.writeIndex - var0.readIndex);
            unlock();
            return var0.capacity - (int) (var0.writeIndex - var0.readIndex);
        }
    }
    public void clear() {
        {
            var1 = var0.lock;
            lock();
            fill(var0.buffer, null);
            var0.writeIndex = 0L;
            var0.readIndex = 0L;
            signalAll();
            unlock();
        }
        return;
    }
    public java.util.Iterator iterator() {
        {
            <init>();
            new java.lang.Object();
        }
    }
    public java.lang.String toString() {
        {
            size();
            return invokeDynamic();
        }
    }
}
