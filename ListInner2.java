import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import java.nio.file.*;

public class ListInner2 {
    public static void main(String[] args) throws Exception {
        Path testClassesDir = Paths.get("target/test-classes/");
        byte[] bytes = Files.readAllBytes(
            testClassesDir.resolve("com/bytecode/test/EnumDemo.class"));
        ClassFileReader reader = new ClassFileReader();
        ClassFileModel cfm = reader.read("com/bytecode/test/EnumDemo", bytes);
        System.out.println("EnumDemo class flags: 0x" + Integer.toHexString(cfm.accessFlags()));
        System.out.println("Inner classes count: " + cfm.innerClasses().size());
        for (var ice : cfm.innerClasses()) {
            System.out.println("  innerClass=" + ice.innerClass() 
                + " outerClass=" + ice.outerClass() 
                + " simpleName=" + ice.simpleName()
                + " flags=0x" + Integer.toHexString(ice.accessFlags()));
        }
    }
}
