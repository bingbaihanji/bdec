class RecordSealedSample {
    sealed interface Shape permits Circle, Square {
        double area();
    }

    record Circle(double radius) implements Shape {
        public double area() { return Math.PI * radius * radius; }
    }

    record Square(double side) implements Shape {
        public double area() { return side * side; }
    }

    static double total(java.util.List<Shape> shapes) {
        double sum = 0;
        for (Shape s : shapes) {
            sum += s.area();
        }
        return sum;
    }

    static String classify(Shape s) {
        return switch (s) {
            case Circle c -> "circle " + c.radius();
            case Square sq -> "square " + sq.side();
        };
    }

    static String describe(Object o) {
        if (o instanceof String s) return "string:" + s.length();
        if (o instanceof Integer i) return "int:" + i;
        if (o instanceof Circle c) return "circle:" + c.radius();
        return "other";
    }
}
