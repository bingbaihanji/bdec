class FinallySideEffectCheck {
    static int x;

    static int m() {
        try {
            return 1;
        } finally {
            x++;
        }
    }

    public static String check() {
        x = 0;
        int r = m();
        return "r=" + r + ";x=" + x;
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
