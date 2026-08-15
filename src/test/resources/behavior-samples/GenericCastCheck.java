class GenericCastCheck {

    static Object[] arr = {"a", Integer.valueOf(1)};

    static String at(int i) {
        return (String) arr[i];
    }

    public static String check() {
        StringBuilder sb = new StringBuilder();
        sb.append(at(0));
        try {
            at(1);
            sb.append("X");
        } catch (ClassCastException e) {
            sb.append("C");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
