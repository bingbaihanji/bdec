class OverloadCheck {

    static int f(int x) {
        return x * 2;
    }

    static int f(int x, int y) {
        return x + y;
    }

    static String f(String s) {
        return s + "!";
    }

    public static String check() {
        return f(3) + ";" + f(3, 4) + ";" + f("hi");
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
