class NestedTernaryCheck {

    static int m(int a, int b, int c, int d, int e) {
        return a > 0 ? (b > 0 ? c : d) : e;
    }

    public static String check() {
        return "v=" + m(1, 5, 2, 3, 4) + ";w=" + m(1, -5, 2, 3, 4) + ";u=" + m(-1, 5, 2, 3, 4);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
