import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

class TryWithResourcesSample {
    static void readFile(String path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(Paths.get(path))) {
            System.out.println(br.readLine());
        }
    }

    static void multiCatch(String s) {
        try {
            int x = Integer.parseInt(s);
            System.out.println(x / 0);
        } catch (NumberFormatException | ArithmeticException e) {
            System.out.println("bad: " + e.getMessage());
        }
    }
}
