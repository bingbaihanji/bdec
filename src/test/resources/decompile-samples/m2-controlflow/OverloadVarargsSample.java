class OverloadVarargsSample {

    static int max(int a, int b) {return a > b ? a : b;}

    static int max(int a, int b, int c) {return max(max(a, b), c);}

    static int max(int... nums) {
        int m = Integer.MIN_VALUE;
        for (int n : nums) {
            if (n > m) {
                m = n;
            }
        }
        return m;
    }

    static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    static void both(String s) {System.out.println("str:" + s);}

    static void both(Object o) {System.out.println("obj:" + o);}

    static String call() {
        both("x");
        both((Object) "y");
        return join("-", "a", "b", "c");
    }
}
