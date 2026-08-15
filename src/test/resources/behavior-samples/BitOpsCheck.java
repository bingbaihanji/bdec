class BitOpsCheck {

    static int f(int x) {
        return (x << 2) | (x >> 1) ^ (x & 0x0F) | (x >>> 2);
    }

    public static String check() {
        return "" + f(5);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
