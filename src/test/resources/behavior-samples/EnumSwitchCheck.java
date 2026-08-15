class EnumSwitchCheck {

    static int f(Color c) {
        switch (c) {
            case RED:
                return 1;
            case GREEN:
                return 2;
            case BLUE:
                return 3;
            default:
                return 0;
        }
    }

    public static String check() {
        StringBuilder sb = new StringBuilder();
        sb.append(f(Color.RED)).append(';');
        sb.append(f(Color.GREEN)).append(';');
        sb.append(f(Color.BLUE)).append(';');
        try {
            f(null);
            sb.append("no-npe");
        } catch (NullPointerException e) {
            sb.append("npe");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }

    enum Color {
        RED,
        GREEN,
        BLUE
    }
}
