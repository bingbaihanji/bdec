class TernaryCompoundAssignCheck {
    static int sum(int[] arr) {
        int s = 0;
        for (int i = 0; i < arr.length; i++) {
            s += arr[i] > 0 ? 1 : 0;
        }
        return s;
    }

    public static String check() {
        return "s=" + sum(new int[] {1, -1, 2});
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
