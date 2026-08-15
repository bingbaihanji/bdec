class DoWhileCheck {
    static int count(int n) {
        int c = 0;
        int i = 0;
        do {
            c++;
            i++;
        } while (i < n);
        return c;
    }

    public static String check() {
        return count(3) + ";" + count(0);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
