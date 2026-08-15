class StaticInitCheck {

    static int base;

    static int[] arr;

    static {
        base = 5;
        arr = new int[3];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = i * 10 + base;
        }
    }

    public static String check() {
        return base + ";" + arr[0] + ";" + arr[2];
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
