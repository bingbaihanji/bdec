class SyncBlockSample {

    private final Object lock = new Object();

    private int count = 0;

    synchronized int inc() {
        return ++count;
    }

    void batch(int n) {
        synchronized (lock) {
            count += n;
        }
    }

    void syncStatic() {
        synchronized (SyncBlockSample.class) {
            count++;
        }
    }

    String syncOnThis() {
        synchronized (this) {
            return String.valueOf(count);
        }
    }
}
