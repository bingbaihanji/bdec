package test;

public class EnumSwitchSample {

    public int test(Color c) {
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

    enum Color {
        RED,
        GREEN,
        BLUE
    }
}
