package test;

/** M2 control flow test: switch-case. */
public class SwitchSample {
    public String test(int n) {
        switch (n) {
            case 1: return "one";
            case 2: return "two";
            default: return "other";
        }
    }
}
