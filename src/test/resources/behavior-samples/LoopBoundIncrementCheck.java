class LoopBoundIncrementCheck {

    static int sum(int n) {
        int s = 0;
        for (int j = 0; j < n; j++) {
            s += j;
        }
        return s;
    }

    static int count(int n) {
        int c = 0;
        int i = 0;
        while (i < n) {
            c++;
            i++;
        }
        return c;
    }

    public static String check() {
        return "sum=" + sum(5) + ";count=" + count(3);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
