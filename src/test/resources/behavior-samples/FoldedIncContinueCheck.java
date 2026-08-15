class FoldedIncContinueCheck {

    static int sum(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                continue;
            }
            s += i;
        }
        return s;
    }

    public static String check() {
        return "" + sum(6);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
