class SyncInstanceCheck {

    private int counter;

    public static String check() {
        return new SyncInstanceCheck().run();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }

    int m() {
        synchronized (this) {
            counter++;
        }
        return counter;
    }

    public String run() {
        m();
        m();
        return "" + counter;
    }
}
