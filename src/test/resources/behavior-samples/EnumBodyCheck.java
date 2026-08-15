class EnumBodyCheck {

    public static String check() {
        return Color.RED.desc() + "|" + Color.BLUE.desc();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }

    enum Color {
        RED {
            public String desc() {
                return "red";
            }
        },
        BLUE {
            public String desc() {
                return "blue";
            }
        };

        public abstract String desc();
    }
}
