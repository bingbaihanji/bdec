class SwitchExprCheck {
    static int f(int x) {
        return switch (x) {
            case 1 -> 10;
            case 2, 3 -> 20;
            case 4 -> {
                yield 8;
            }
            default -> x;
        };
    }

    public static String check() {
        return "1=" + f(1) + ";2=" + f(2) + ";3=" + f(3) + ";4=" + f(4) + ";99=" + f(99);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
