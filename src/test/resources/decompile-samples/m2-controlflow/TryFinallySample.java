package test;

import java.util.concurrent.locks.ReentrantLock;

/** M2 control flow test: try-finally with lock/unlock pattern. */
public class TryFinallySample {

    private final ReentrantLock lock = new ReentrantLock();

    public int test() {
        lock.lock();
        try {
            return 42;
        } finally {
            lock.unlock();
        }
    }
}
