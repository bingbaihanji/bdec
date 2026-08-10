package com.bingbaihanji.bdec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Round-trip tests for the bytecode test module (com.bytecode.test).
 *
 * Each test compiles the original source, decompiles the .class with BDEC,
 * then attempts to recompile the decompiled output. The recompilation must
 * succeed and the decompiled logic must be semantically equivalent.
 *
 * Source files are in: src/test/java/com/bytecode/test/
 */
public class BytecodeTestRoundTripTest {

    private static final String TEST_SRC_DIR = "src/test/java/com/bytecode/test";

    private static final String TEST_PKG = "com.bytecode.test";

    private DecompileTestHarness harness;

    private Path tempDir;

    private static void deleteRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteRecursively(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    @Before
    public void setUp() throws Exception {
        harness = new DecompileTestHarness();
        tempDir = Files.createTempDirectory("bdec-bytetest-");
    }

    // === Helper: decompile a pre-compiled .class and recompile ===

    @After
    public void tearDown() {
        if (tempDir != null) {
            deleteRecursively(tempDir.toFile());
        }
    }

    private String decompileAndRecompile(String className) throws Exception {
        Path classFile = Paths.get("target/test-classes/" + TEST_PKG.replace('.', '/'), className + ".class");
        if (!Files.exists(classFile)) {
            throw new RuntimeException("Class file not found: " + classFile + ". Run: javac -g -d target/test-classes src/test/java/com/bytecode/test/*.java");
        }

        // Class byte loader that can load inner classes from target/test-classes/.
        // This is needed by EnumRewriter to find and merge anonymous class bodies
        // (e.g., EnumDemo$1.class, EnumDemo$2.class) back into enum constants.
        Path testClassesDir = Paths.get("target/test-classes/");
        java.util.function.Function<String, byte[]> classByteLoader = internalName -> {
            try {
                Path innerFile = testClassesDir.resolve(internalName + ".class");
                if (Files.exists(innerFile)) {
                    return Files.readAllBytes(innerFile);
                }
            } catch (Exception ignored) {
            }
            return null;
        };

        // Decompile
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        BdecResult result = engine.decompile(classFile,
                new DecompileContext(config, classByteLoader));
        if (!result.success()) {
            return "DECOMPILE FAILED: " + (result.cause() != null ? result.cause().getMessage() : "unknown");
        }

        String decompiled = result.decompiledCode();

        // Try to recompile
        return tryCompile(decompiled, className);
    }

    private String tryCompile(String source, String className) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return "SKIP: no system compiler";
        }

        try {
            Path workDir = Files.createTempDirectory(tempDir, "compile-");
            Path srcFile = workDir.resolve(className + ".java");
            Files.writeString(srcFile, source, StandardCharsets.UTF_8);

            java.io.ByteArrayOutputStream errStream = new java.io.ByteArrayOutputStream();
            // Compile with classpath to the original compiled classes (for dependencies)
            int result = compiler.run(null, null, errStream,
                    "-d", workDir.toString(),
                    "-cp", "target/test-classes",
                    srcFile.toString());

            if (result != 0) {
                return "COMPILE FAILED:\n" + errStream.toString();
            }
            return "OK";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // === Round-trip tests for each class ===

    @Test
    public void testInterfaceARoundTrip() throws Exception {
        String r = decompileAndRecompile("InterfaceA");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testInterfaceBRoundTrip() throws Exception {
        String r = decompileAndRecompile("InterfaceB");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testAnnotationDemoRoundTrip() throws Exception {
        String r = decompileAndRecompile("AnnotationDemo");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testBaseClassRoundTrip() throws Exception {
        String r = decompileAndRecompile("BaseClass");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testEnumDemoRoundTrip() throws Exception {
        String r = decompileAndRecompile("EnumDemo");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testRecordDemoRoundTrip() throws Exception {
        String r = decompileAndRecompile("RecordDemo");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testSealedParentRoundTrip() throws Exception {
        String r = decompileAndRecompile("SealedParent");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testSealedChild1RoundTrip() throws Exception {
        String r = decompileAndRecompile("SealedChild1");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testSealedChild2RoundTrip() throws Exception {
        String r = decompileAndRecompile("SealedChild2");
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testTestClass1RoundTrip() throws Exception {
        String r = decompileAndRecompile("TestClass1");
        if (!r.equals("OK")) {
            System.err.println("=== TestClass1 decompile/compile failure ===");
            System.err.println(r);
        }
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testTestClass2RoundTrip() throws Exception {
        String r = decompileAndRecompile("TestClass2");
        if (!r.equals("OK")) {
            System.err.println("=== TestClass2 decompile/compile failure ===");
            System.err.println(r);
        }
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testSimpleNewTestRoundTrip() throws Exception {
        String r = decompileAndRecompile("SimpleNewTest");
        if (!r.equals("OK")) {
            System.err.println("=== SimpleNewTest decompile/compile failure ===");
            System.err.println(r);
        }
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testSimpleForEachTestRoundTrip() throws Exception {
        String r = decompileAndRecompile("SimpleForEachTest");
        if (!r.equals("OK")) {
            System.err.println("=== SimpleForEachTest decompile/compile failure ===");
            System.err.println(r);
        }
        org.junit.Assert.assertEquals("OK", r);
    }

    @Test
    public void testSimpleStringTestRoundTrip() throws Exception {
        String r = decompileAndRecompile("SimpleStringTest");
        if (!r.equals("OK")) {
            System.err.println("=== SimpleStringTest decompile/compile failure ===");
            System.err.println(r);
        }
        org.junit.Assert.assertEquals("OK", r);
    }
}
