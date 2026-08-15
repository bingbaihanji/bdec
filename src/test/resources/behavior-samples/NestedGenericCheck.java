class NestedGenericCheck {

    static String go() {
        java.util.Map<String, java.util.List<Integer>> m =
                new java.util.HashMap<String, java.util.List<Integer>>();
        java.util.List<Integer> list = new java.util.ArrayList<Integer>();
        list.add(10);
        list.add(20);
        m.put("k", list);
        int total = 0;
        for (Integer v : m.get("k")) {
            total += v;
        }
        return m.size() + ";" + total;
    }

    public static String check() {
        return go();
    }

    public static void main(String[] args) {
        System.out.println(check());
    }
}
