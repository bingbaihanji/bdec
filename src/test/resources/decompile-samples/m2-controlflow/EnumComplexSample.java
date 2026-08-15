enum EnumComplexSample {
    RED("r", 1) {
        @Override
        public String code() {return "R";}
    },
    GREEN("g", 2) {
        @Override
        public String code() {return "G";}
    };

    private final String letter;

    private final int num;

    EnumComplexSample(String letter, int num) {
        this.letter = letter;
        this.num = num;
    }

    static String describe(EnumComplexSample e) {
        return switch (e) {
            case RED -> "red-" + e.code();
            case GREEN -> "green-" + e.code();
        };
    }

    static EnumComplexSample find(String letter) {
        for (EnumComplexSample e : values()) {
            if (e.letter.equals(letter)) {
                return e;
            }
        }
        return null;
    }

    public abstract String code();

    public String letter() {return letter;}

    public int num() {return num;}
}
