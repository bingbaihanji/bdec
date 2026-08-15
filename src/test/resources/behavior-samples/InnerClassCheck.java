class InnerClassCheck {

    public static String check() {
        Host h = new Host();
        return "" + h.run();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }

    static class Host {

        int base = 5;

        int run() {
            return new Using().get() + new Plain().get();
        }

        class Using {

            int get() {
                return base + 1;
            }
        }

        class Plain {

            int get() {
                return 42;
            }
        }
    }
}
