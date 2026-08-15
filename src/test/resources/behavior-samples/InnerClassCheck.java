class InnerClassCheck {
    static class Host {
        int base = 5;

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

        int run() {
            return new Using().get() + new Plain().get();
        }
    }

    public static String check() {
        Host h = new Host();
        return "" + h.run();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
