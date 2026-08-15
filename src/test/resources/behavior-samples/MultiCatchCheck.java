class MultiCatchCheck {
    static String f(int x) {
        try {
            if (x == 1) {
                throw new IllegalStateException("s");
            }
            if (x == 2) {
                throw new NumberFormatException("n");
            }
            return "ok";
        } catch (IllegalStateException | NumberFormatException e) {
            return e.getClass().getSimpleName();
        }
    }

    public static String check() {
        return f(0) + ";" + f(1) + ";" + f(2);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
