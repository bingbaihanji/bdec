package com.bingbaihanji.common.framework.utils.queue;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class RingBuffer<E> extends AbstractQueue {
    private static final int MAXIMUM_CAPACITY = 1073741824;
    private final Object[] buffer;
    private final int mask;
    private final int capacity;
    private final ReentrantLock lock;
    private final Condition notFull;
    private final Condition notEmpty;
    private long writeIndex;
    private long readIndex;
    public RingBuffer(int capacity) {
        this(capacity, false);
    }
    public RingBuffer(int capacity, boolean fair) {
        if (capacity > 0) {
            this.capacity = RingBuffer.calculateThresholdCapacity(capacity);
            this.mask = this.capacity - 1;
            this.buffer = new Object[this.capacity];
            this.lock = new ReentrantLock(fair);
            this.notFull = lock.newCondition();
            this.notEmpty = lock.newCondition();
            return;
        }
         else {
            throw new IllegalArgumentException("Capacity must be positive");
        }
    }
    private static int calculateThresholdCapacity(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return n >= 0 ? n < 1073741824 ? n + 1 : 1073741824 : 1;
    }
    public boolean offer(Object element) {
        {
            ReentrantLock lock = this.lock;
            lock.lock();
        }
        try {
            if (writeIndex - readIndex < (long) capacity) {
                this.enqueue(element);
                lock.unlock();
                return true;
            }
             else {
                lock.unlock();
                return false;
            }
            while (true) {
                lock.unlock();
                {
                    Throwable var4;
                    throw var4;
                }
            }
        }
         finally {
            lock.unlock();
        }
    }
    public Object poll() {
        {
            ReentrantLock lock = this.lock;
            lock.lock();
        }
        try {
            if (readIndex < writeIndex) {
                Object var2 = this.dequeue();
                lock.unlock();
                return var2;
            }
             else {
                lock.unlock();
                return null;
            }
        }
         finally {
            lock.unlock();
        }
    }
    public Object peek() {
        {
            ReentrantLock lock = this.lock;
            lock.lock();
        }
        try {
            if (readIndex < writeIndex) {
                Object var2 = buffer[(int) (readIndex & (long) mask)];
                lock.unlock();
                return var2;
            }
             else {
                lock.unlock();
                return null;
            }
        }
         finally {
            lock.unlock();
        }
    }
    public void put(Object element) {
        {
            ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
        }
        try {
            this.enqueue(element);
            return;
        }
         finally {
            lock.unlock();
        }
    }
    public boolean offer(Object element, long timeout, TimeUnit unit) {
        {
            long nanos = unit.toNanos(timeout);
            this.lock.lockInterruptibly();
        }
        try {
            if (true) {
                this.enqueue(element);
                int var8 = 1;
            }
             else {
                int var8 = 0;
            }
        }
         finally {
            this.lock.unlock();
        }
    }
    public Object take() {
        {
            ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
        }
        try {
            Object var2 = this.dequeue();
            return var2;
        }
         finally {
            lock.unlock();
        }
    }
    public Object poll(long timeout, TimeUnit unit) {
        {
            long nanos = unit.toNanos(timeout);
            this.lock.lockInterruptibly();
        }
        try {
            if (true) {
                Object var7 = this.dequeue();
            }
             else {
                Object var7 = null;
            }
        }
         finally {
            this.lock.unlock();
        }
    }
    public int drainTo(Object[] dest) {
        if (dest.length != 0) {
            return this.lock.lock();
            int transferCount = 0;
            int transferCount = Math.min(available, dest.length);
            int i = 0;
            return notFull.signalAll();
            return i = transferCount;
        }
         else {
            return 0;
        }
    }
    private void enqueue(Object element) {
        buffer[(int) (writeIndex & (long) mask)] = element;
        this.writeIndex += 1L;
        notEmpty.signal();
        return;
    }
    private Object dequeue() {
        int index = (int) (readIndex & (long) mask);
        Object element = buffer[index];
        buffer[index] = null;
        this.readIndex += 1L;
        notFull.signal();
        return element;
    }
    public int size() {
        {
            ReentrantLock lock = this.lock;
            lock.lock();
        }
        try {
            return (int) (writeIndex - readIndex);
        }
         finally {
            lock.unlock();
        }
    }
    public boolean isEmpty() {
        return this.size() != 0 ? false : true;
    }
    public int capacity() {
        return capacity;
    }
    public int remainingCapacity() {
        {
            ReentrantLock lock = this.lock;
            lock.lock();
        }
        try {
            int var2 = capacity - (int) (writeIndex - readIndex);
            return var2;
        }
         finally {
            lock.unlock();
        }
    }
    public void clear() {
        {
            ReentrantLock lock = this.lock;
            lock.lock();
        }
        try {
            Arrays.fill(buffer, null);
            this.writeIndex = 0L;
            this.readIndex = 0L;
            notFull.signalAll();
            return;
        }
         finally {
            lock.unlock();
        }
    }
    public Iterator iterator() {
        throw new UnsupportedOperationException("Iterator directly on thread-safe RingBuffer is not supported");
    }
    public String toString() {
        return "" + this.size() + capacity;
    }
}
