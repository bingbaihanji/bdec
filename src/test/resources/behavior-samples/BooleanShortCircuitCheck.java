class BooleanShortCircuitCheck {
    static int calls;

    static boolean side(boolean v) {
        calls++;
        return v;
    }

    static int and(boolean a, boolean b) {
        calls = 0;
        boolean r = side(a) && side(b);
        return (r ? 1 : 0) * 10 + calls;
    }

    static int or(boolean a, boolean b) {
        calls = 0;
        boolean r = side(a) || side(b);
        return (r ? 1 : 0) * 10 + calls;
    }

    public static String check() {
        return and(false, true) + ";" + and(true, false) + ";" + or(true, false) + ";" + or(false, true);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
