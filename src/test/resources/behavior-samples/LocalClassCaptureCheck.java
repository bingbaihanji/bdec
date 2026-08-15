class LocalClassCaptureCheck {

    static int run() {
        int x = 5;
        class Local {

            int get() {
                return x + 1;
            }
        }
        Local l = new Local();
        return l.get();
    }

    public static String check() {
        return "" + run();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
