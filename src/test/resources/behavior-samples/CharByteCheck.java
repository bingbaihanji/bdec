class CharByteCheck {

    static int calc(byte a, char c) {
        byte b = (byte) (a + 1);
        char d = (char) (c + 1);
        return b + d;
    }

    public static String check() {
        return calc((byte) 10, 'A') + ";" + calc((byte) -5, 'z');
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
