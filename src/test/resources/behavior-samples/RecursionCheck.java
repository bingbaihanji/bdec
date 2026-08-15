class RecursionCheck {

    static int fib(int n) {
        if (n <= 1) {
            return n;
        }
        return fib(n - 1) + fib(n - 2);
    }

    public static String check() {
        return "" + fib(10);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
