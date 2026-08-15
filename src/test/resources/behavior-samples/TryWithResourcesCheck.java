class TryWithResourcesCheck {

    static final StringBuilder orderLog = new StringBuilder();

    static String twr() {
        StringBuilder sb = new StringBuilder();
        try (R a = new R("aa"); R b = new R("bb")) {
            sb.append("body");
        }
        return sb.toString() + "|" + orderLog;
    }

    public static String check() {
        orderLog.setLength(0);
        return twr();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }

    static class R implements AutoCloseable {

        final String name;

        R(String n) {
            this.name = n;
        }

        public void close() {
            orderLog.append(name);
        }
    }
}
