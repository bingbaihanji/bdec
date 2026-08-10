package com.bingbaihanji.bdec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Quick debug utility to decompile a class and print the output.
 */
public class DebugDecompile {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: DebugDecompile <class-name>");
            System.out.println("Example: DebugDecompile SimpleForEachTest");
            return;
        }
        String className = args[0];
        Path classFile = Paths.get("target/test-classes/com/bytecode/test/" + className + ".class");
        if (!Files.exists(classFile)) {
            System.out.println("Class file not found: " + classFile);
            return;
        }

        Path testClassesDir = Paths.get("target/test-classes/");
        java.util.function.Function<String, byte[]> classByteLoader = internalName -> {
            try {
                Path innerFile = testClassesDir.resolve(internalName + ".class");
                if (Files.exists(innerFile)) {
                    return Files.readAllBytes(innerFile);
                }
            } catch (Exception ignored) {}
            return null;
        };

        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        BdecResult result = engine.decompile(classFile,
                new DecompileContext(config, classByteLoader));
        if (result.success()) {
            System.out.println("=== Decompiled " + className + " ===");
            System.out.println(result.decompiledCode());
        } else {
            System.out.println("Decompile failed: " +
                (result.cause() != null ? result.cause().getMessage() : "unknown"));
        }
    }
}
