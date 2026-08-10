package test;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** M2: Complex lock/try-finally patterns matching RingBuffer structure. */
public class LockTryFinallySample {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private int count;

    // Pattern: lock + try-finally with return
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    // Pattern: if-else with void calls in both branches
    public boolean offer(int value) {
        lock.lock();
        try {
            if (count < 10) {
                count++;
                return true;
            } else {
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    // Pattern: lock + void method + return
    public void clear() {
        lock.lock();
        try {
            count = 0;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // Pattern: complex if-else with early return
    public int drainTo(int[] dest) {
        if (dest.length == 0) {
            return 0;
        }
        lock.lock();
        try {
            int available = count;
            int transferCount = Math.min(available, dest.length);
            for (int i = 0; i < transferCount; i++) {
                dest[i] = 1;
                count--;
            }
            return transferCount;
        } finally {
            lock.unlock();
            notEmpty.signalAll();
        }
    }
}
