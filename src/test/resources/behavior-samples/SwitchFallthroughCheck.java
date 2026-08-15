class SwitchFallthroughCheck {
    static int f(int x) {
        int r = 0;
        switch (x) {
            case 1:
                r += 1;
            case 2:
                r += 2;
                break;
            case 3:
                r += 3;
                break;
            default:
                r += 10;
        }
        return r;
    }

    public static String check() {
        return f(1) + ";" + f(2) + ";" + f(3) + ";" + f(9);
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
