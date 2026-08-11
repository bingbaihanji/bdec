import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import java.nio.file.*;

public class ListInner {
    public static void main(String[] args) throws Exception {
        Path testClassesDir = Paths.get("target/test-classes/");
        byte[] bytes = Files.readAllBytes(
            testClassesDir.resolve("com/bytecode/test/TestClass2.class"));
        ClassFileReader reader = new ClassFileReader();
        ClassFileModel cfm = reader.read("com/bytecode/test/TestClass2", bytes);
        System.out.println("Inner classes count: " + cfm.innerClasses().size());
        for (var ice : cfm.innerClasses()) {
            System.out.println("  innerClass=" + ice.innerClass() 
                + " outerClass=" + ice.outerClass() 
                + " simpleName=" + ice.simpleName()
                + " flags=" + Integer.toHexString(ice.accessFlags()));
        }
    }
}
