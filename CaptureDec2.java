import com.bingbaihanji.bdec.*;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import java.nio.file.*;
import java.util.function.Function;

public class CaptureDec2 {
    public static void main(String[] args) throws Exception {
        Path testClassesDir = Paths.get("target/test-classes/");
        
        // Try to read the local class directly
        byte[] localClassBytes = Files.readAllBytes(
            testClassesDir.resolve("com/bytecode/test/TestClass2$1LocalClass.class"));
        
        System.out.println("LocalClass bytes: " + localClassBytes.length);
        
        ClassFileReader reader = new ClassFileReader();
        ClassFileModel cfm = reader.read("com/bytecode/test/TestClass2$1LocalClass", localClassBytes);
        System.out.println("Methods: " + cfm.methods().size());
        System.out.println("Fields: " + cfm.fields().size());
        System.out.println("Inner classes: " + cfm.innerClasses().size());
        for (var ice : cfm.innerClasses()) {
            System.out.println("  Inner: " + ice.innerClass() + " outer=" + ice.outerClass() + " simple=" + ice.simpleName());
        }
        for (var m : cfm.methods()) {
            System.out.println("  Method: " + m.name() + m.descriptor());
        }
        for (var f : cfm.fields()) {
            System.out.println("  Field: " + f.name() + " flags=" + f.accessFlags());
        }
        
        // Now try to decompile it
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {
            System.err.println("DIAG: " + d.level() + " " + d.phase() + " " + d.message());
        });
        
        Function<String, byte[]> classByteLoader = internalName -> {
            try {
                Path innerFile = testClassesDir.resolve(internalName + ".class");
                if (Files.exists(innerFile)) return Files.readAllBytes(innerFile);
            } catch (Exception ignored) {}
            return null;
        };
        
        try {
            BdecResult result = engine.decompile(
                testClassesDir.resolve("com/bytecode/test/TestClass2$1LocalClass.class"),
                new DecompileContext(config, classByteLoader));
            System.out.println("SUCCESS: " + (result.success() ? "yes" : "no"));
            if (result.success()) {
                System.out.println(result.decompiledCode());
            } else {
                System.out.println("Error: " + (result.cause() != null ? result.cause().getMessage() : "?"));
                if (result.cause() != null) result.cause().printStackTrace();
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
