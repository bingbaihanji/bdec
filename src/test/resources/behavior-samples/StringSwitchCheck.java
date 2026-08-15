class StringSwitchCheck {
    static int f(String s) {
        switch (s) {
            case "foo":
                return 1;
            case "bar":
                return 2;
            case "Aa":
                return 3;
            case "BB":
                return 4;
            default:
                return 0;
        }
    }

    public static String check() {
        return f("foo") + ";" + f("bar") + ";" + f("Aa") + ";" + f("BB") + ";" + f("x");
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
