class Array2dCheck {

    static int sum(int[][] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a[i].length; j++) {
                s += a[i][j];
            }
        }
        return s;
    }

    public static String check() {
        int[][] a = {{1, 2}, {3, 4, 5}};
        return "" + sum(a);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
