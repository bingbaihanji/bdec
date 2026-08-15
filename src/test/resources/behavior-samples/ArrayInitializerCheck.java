class ArrayInitializerCheck {

    static int[] make() {
        int[] a = {1, 2, 3, 4};
        return a;
    }

    public static String check() {
        int[] a = make();
        return a[0] + ";" + a[3] + ";" + a.length;
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
