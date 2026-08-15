import java.util.List;
import java.util.function.*;

class LambdaClosureSample {
    static Function<Integer, Integer> adder(int base) {
        int offset = base * 2;
        return x -> x + offset;
    }

    static IntSupplier counter() {
        int[] n = {0};
        return () -> n[0]++;
    }

    static List<String> transform(List<String> in) {
        return in.stream()
                .filter(s -> !s.isEmpty())
                .map(s -> s.trim())
                .sorted((a, b) -> a.length() - b.length())
                .toList();
    }

    interface Greeter { String greet(String name); }

    static Greeter makeGreeter(String prefix) {
        return name -> prefix + ", " + name + "!";
    }
}
