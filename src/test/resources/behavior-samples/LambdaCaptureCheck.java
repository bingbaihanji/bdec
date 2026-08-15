class LambdaCaptureCheck {
    static int factor = 5;

    interface Fn {
        int apply(int x);
    }

    static int lambda(int a, int b) {
        Fn f = v -> v * a + b + factor;
        return f.apply(3);
    }

    public static String check() {
        return "" + lambda(2, 5);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
