class TernaryAsOperandCheck {
    static int cond(int a, int b, int c, int d, int e) {
        return (a > 0 ? b : c) > 0 ? d : e;
    }

    public static String check() {
        return "v=" + cond(-1, -5, 3, 10, 20) + ";u=" + cond(1, 2, -1, 10, 20);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
