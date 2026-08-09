package test;

public class EnumSwitchSample {
    enum Color { RED, GREEN, BLUE }

    public int test(Color c) {
        switch (c) {
            case RED: return 1;
            case GREEN: return 2;
            case BLUE: return 3;
            default: return 0;
        }
    }
}
