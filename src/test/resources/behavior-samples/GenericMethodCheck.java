class GenericMethodCheck {
    static <T> T pick(boolean b, T x, T y) {
        return b ? x : y;
    }

    public static String check() {
        String s = pick(true, "a", "b");
        Integer n = pick(false, 1, 2);
        return s + ";" + n;
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
