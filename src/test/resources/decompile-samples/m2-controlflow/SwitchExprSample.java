class SwitchExprSample {

    static String dayName(int day) {
        return switch (day) {
            case 1 -> "mon";
            case 2 -> "tue";
            case 3, 4 -> "mid";
            default -> "weekend";
        };
    }

    static int dayNum(String name) {
        return switch (name) {
            case "mon" -> 1;
            case "tue" -> 2;
            default -> {
                int len = name.length();
                yield len > 3 ? 9 : 0;
            }
        };
    }

    static String arrowWithFallthrough(int x) {
        String r;
        switch (x) {
            case 1:
                r = "one";
                break;
            case 2:
            case 3:
                r = "two-three";
                break;
            default:
                r = "other";
        }
        return r;
    }
}
