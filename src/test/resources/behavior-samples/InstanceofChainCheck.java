class InstanceofChainCheck {

    static String f(Object o) {
        if (o instanceof String) {
            return "s:" + o;
        }
        if (o instanceof Integer) {
            return "i:" + o;
        }
        if (o instanceof Long) {
            return "l:" + o;
        }
        return "other";
    }

    public static String check() {
        return f("x") + "|" + f(5) + "|" + f(5L) + "|" + f(1.5);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
