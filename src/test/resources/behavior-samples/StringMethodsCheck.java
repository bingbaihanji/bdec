class StringMethodsCheck {

    static String f(String s) {
        return s.substring(2, 5) + "|" + s.charAt(0) + "|" + s.indexOf('o');
    }

    public static String check() {
        return f("hello world");
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
