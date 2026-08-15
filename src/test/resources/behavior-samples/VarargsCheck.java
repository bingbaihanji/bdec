class VarargsCheck {

    static int sumAll(int... nums) {
        int s = 0;
        for (int i = 0; i < nums.length; i++) {
            s += nums[i];
        }
        return s;
    }

    public static String check() {
        return sumAll(1, 2, 3) + ";" + sumAll(new int[]{4, 5});
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
