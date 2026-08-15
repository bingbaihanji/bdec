class StringConcatCheck {
    static String concat(String a, String b, int n, Object o) {
        return a + b + n + ":" + o;
    }

    public static String check() {
        return concat("He", "llo", 42, null);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
