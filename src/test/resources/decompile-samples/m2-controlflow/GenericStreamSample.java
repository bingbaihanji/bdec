import java.util.*;
import java.util.stream.*;

class GenericStreamSample {
    static List<String> filterStrings(List<String> items) {
        return items.stream().filter(s -> s.length() > 2)
                .map(String::toUpperCase).collect(Collectors.toList());
    }

    static Map<String, Integer> countWords(String[] words) {
        Map<String, Integer> counts = new HashMap<>();
        for (String w : words) {
            counts.merge(w, 1, Integer::sum);
        }
        return counts;
    }

    static <T> T firstOrNull(List<T> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    static Optional<Integer> maxEven(List<Integer> nums) {
        return nums.stream().filter(n -> n % 2 == 0).max(Integer::compareTo);
    }

    static List<? extends Number> widen(List<Integer> ints) {
        return ints;
    }
}
