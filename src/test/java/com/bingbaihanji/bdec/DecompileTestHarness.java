package com.bingbaihanji.bdec;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Round-trip test harness: compiles a .java source with javac,
 * decompiles the resulting .class with BDEC, and returns the output.
 *
 * <p>Uses JDK built-in {@link javax.tools.JavaCompiler}, zero external deps.
 */
public class DecompileTestHarness {

    private final BdecConfig config;

    public DecompileTestHarness() {
        this.config = BdecConfig.builder().build();
    }

    public DecompileTestHarness(BdecConfig config) {
        this.config = config;
    }

    /**
     * Compile a .java resource file, then decompile it with BDEC.
     *
     * @param resourcePath path relative to src/test/resources/ (e.g. "m2-controlflow/IfElseSample.java")
     * @return the decompiled Java source text
     */
    public String decompileResource(String resourcePath) throws Exception {
        Path resourceFile = findResource(resourcePath);
        String source = Files.readString(resourceFile, StandardCharsets.UTF_8);
        return decompileSource(source, resourcePath);
    }

    /**
     * Compile a Java source string, then decompile the first .class found.
     */
    public String decompileSource(String source, String className) throws Exception {
        Path tmpDir = Files.createTempDirectory("bdec-test-");
        try {
            // Write source to temp file
            Path srcFile = tmpDir.resolve(extractSimpleName(className) + ".java");
            Files.writeString(srcFile, source, StandardCharsets.UTF_8);

            // Compile with javac
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new RuntimeException("No system Java compiler available. Run with JDK, not JRE.");
            }
            int compileResult = compiler.run(null, null, null,
                    "-g", "-d", tmpDir.toString(),
                    srcFile.toString());
            if (compileResult != 0) {
                throw new RuntimeException("Compilation failed for: " + className);
            }

            // Find the .class file
            List<Path> classFiles = Files.walk(tmpDir)
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();
            if (classFiles.isEmpty()) {
                throw new RuntimeException("No .class file produced for: " + className);
            }

            // Decompile the first class file
            Path classFile = classFiles.get(0);
            BdecEngine engine = new BdecEngine(config, d -> {});
            BdecResult result = engine.decompile(classFile,
                    DecompileContext.empty(config));
            if (!result.success()) {
                throw new RuntimeException("Decompilation failed: " + result.cause());
            }
            return result.decompiledCode();
        } finally {
            // Cleanup temp dir
            deleteRecursively(tmpDir);
        }
    }

    /** Assert output contains ALL of the given patterns. */
    public static void assertContains(String output, String... patterns) {
        for (String pattern : patterns) {
            assertTrue("Expected output to contain: " + pattern,
                    output.contains(pattern));
        }
    }

    /** Assert output does NOT contain ANY of the given patterns. */
    public static void assertNotContains(String output, String... patterns) {
        for (String pattern : patterns) {
            assertFalse("Expected output NOT to contain: " + pattern,
                    output.contains(pattern));
        }
    }

    /** Assert output contains all patterns (case-insensitive). */
    public static void assertContainsIgnoreCase(String output, String... patterns) {
        String lower = output.toLowerCase();
        for (String pattern : patterns) {
            assertTrue("Expected output to contain (case-insensitive): " + pattern,
                    lower.contains(pattern.toLowerCase()));
        }
    }

    private Path findResource(String resourcePath) {
        // Try classpath first (Maven copies src/test/resources to classpath root)
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                Path tmp = Files.createTempFile("bdec-res-", ".java");
                Files.write(tmp, is.readAllBytes());
                tmp.toFile().deleteOnExit();
                return tmp;
            }
        } catch (IOException ignored) {
        }
        // Fallback: try as filesystem path
        Path filePath = Paths.get("src/test/resources", resourcePath);
        if (Files.exists(filePath)) {
            return filePath;
        }
        throw new RuntimeException("Resource not found: " + resourcePath);
    }

    private String extractSimpleName(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        return name;
    }

    private void deleteRecursively(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    files.forEach(this::deleteRecursively);
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }
}
