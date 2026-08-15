class SynchronizedCheck {
    static int counter;

    static int m() {
        synchronized (SynchronizedCheck.class) {
            counter++;
        }
        return counter;
    }

    public static String check() {
        counter = 0;
        m();
        m();
        return "" + counter;
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
