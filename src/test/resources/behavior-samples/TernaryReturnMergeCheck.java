class TernaryReturnMergeCheck {

    static int fooCount;

    static int foo() {
        fooCount++;
        return 1;
    }

    static int bar() {
        return 2;
    }

    static int m(boolean b) {
        int y = b ? foo() : bar();
        return y;
    }

    public static String check() {
        fooCount = 0;
        int y1 = m(true);
        int f1 = fooCount;
        fooCount = 0;
        int y2 = m(false);
        return "y1=" + y1 + ";y2=" + y2 + ";foo=" + f1;
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
