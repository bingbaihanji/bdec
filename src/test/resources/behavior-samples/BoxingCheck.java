class BoxingCheck {

    static int idCompare() {
        Integer a = 1000;
        Integer b = 1000;
        return a == b ? 1 : 0;
    }

    static int sum() {
        Integer a = 1000;
        Integer b = 1000;
        return a + b;
    }

    public static String check() {
        return "id=" + idCompare() + ";sum=" + sum();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
