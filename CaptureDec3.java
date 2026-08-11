import com.bingbaihanji.bdec.*;
import java.nio.file.*;
import java.util.function.Function;

public class CaptureDec3 {
    public static void main(String[] args) throws Exception {
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        Path testClassesDir = Paths.get("target/test-classes/");
        Function<String, byte[]> classByteLoader = internalName -> {
            try {
                Path innerFile = testClassesDir.resolve(internalName + ".class");
                if (Files.exists(innerFile)) return Files.readAllBytes(innerFile);
            } catch (Exception ignored) {}
            return null;
        };
        Path classFile = testClassesDir.resolve("com/bytecode/test/EnumDemo.class");
        BdecResult result = engine.decompile(classFile,
                new DecompileContext(config, classByteLoader));
        if (result.success()) {
            System.out.println(result.decompiledCode());
        } else {
            System.out.println("FAILED: " + (result.cause() != null ? result.cause().getMessage() : "?"));
        }
    }
}
