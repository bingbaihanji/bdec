package com.bingbaihanji.common.framework.utils.queue;

import java.util.AbstractQueue;
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
            return;
        }
         else {
            throw new IllegalArgumentException("Capacity must be positive");
        }
    }
    private static int calculateThresholdCapacity(int cap) {
        return n >= 0 ? 1073741824 : 1;
    }
    public boolean offer(Object element) {
        {
            lock = this.lock;
            lock.lock();
        }
        try {
            if (writeIndex - readIndex < (long) capacity) {
                lock.unlock();
                throw this;
                this.enqueue(element);
                lock.unlock();
                return true;
            }
             else {
                lock.unlock();
                return false;
            }
        }
         catch (Throwable e) {
        }
    }
    public Object poll() {
        {
            lock = this.lock;
            lock.lock();
        }
        try {
            if (readIndex < writeIndex) {
                var2 = this.dequeue();
                lock.unlock();
                return var2;
                lock.unlock();
                throw var3;
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
            lock = this.lock;
            lock.lock();
        }
        try {
            if (readIndex < writeIndex) {
                var2 = buffer[(int) (readIndex & (long) mask)];
                lock.unlock();
                return var2;
                lock.unlock();
                throw var3;
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
            lock = this.lock;
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
            this = this.toNanos(timeout);
            this = lock;
            this.lockInterruptibly();
        }
        try {
            if (/*condition*/) {
                this.enqueue(element);
                this = 1;
            }
             else {
                this = 0;
            }
        }
         catch (Throwable e) {
        }
    }
    public Object take() {
        {
            lock = this.lock;
            lock.lockInterruptibly();
        }
        try {
            var2 = this.dequeue();
            return var2;
        }
         finally {
            lock.unlock();
        }
    }
    public Object poll(long timeout, TimeUnit unit) {
        {
            this = unit.toNanos(timeout);
            this = lock;
            this.lockInterruptibly();
        }
        try {
            if (/*condition*/) {
                this = this.dequeue();
            }
             else {
                this = null;
            }
        }
         catch (Throwable e) {
        }
    }
    public int drainTo(Object[] dest) {
        if (dest.length != 0) {
            return this.lock.lock();
            return this = 0;
            return this = Math.min(available, dest.length);
            return 0.notFull.signalAll();
            return this = 0;
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
        index = (int) (readIndex & (long) mask);
        element = buffer[index];
        buffer[index] = null;
        this.readIndex += 1L;
        notFull.signal();
        return element;
    }
    public int size() {
        {
            lock = this.lock;
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
            lock = this.lock;
            lock.lock();
        }
        try {
            var2 = capacity - (int) (writeIndex - readIndex);
            return var2;
        }
         finally {
            lock.unlock();
        }
    }
    public void clear() {
        {
            lock = this.lock;
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
        return this.size() + capacity;
    }
}
