class AnonymousClassCheck {

    static int run() {
        final int base = 10;
        Fn f = new Fn() {

            public int apply(int x) {
                return x * 2 + base;
            }
        };
        return f.apply(3);
    }

    public static String check() {
        return "" + run();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }

    interface Fn {

        int apply(int x);
    }
}
