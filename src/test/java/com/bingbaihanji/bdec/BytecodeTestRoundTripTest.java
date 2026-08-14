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

        // Decompile
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        BdecResult result = engine.decompile(classFile,
                new DecompileContext(config, testClassByteLoader()));
        if (!result.success()) {
            return "DECOMPILE FAILED: " + (result.cause() != null ? result.cause().getMessage() : "unknown");
        }

        String decompiled = result.decompiledCode();

        // Try to recompile
        return tryCompile(decompiled, className);
    }

    /** 仅反编译(不重编译),用于断言输出内容. */
    private String decompileOnly(String className) throws Exception {
        Path classFile = Paths.get("target/test-classes/" + TEST_PKG.replace('.', '/'), className + ".class");
        if (!Files.exists(classFile)) {
            throw new RuntimeException("Class file not found: " + classFile);
        }
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        BdecResult result = engine.decompile(classFile,
                new DecompileContext(config, testClassByteLoader()));
        if (!result.success()) {
            throw new RuntimeException("DECOMPILE FAILED: " + result.cause());
        }
        return result.decompiledCode();
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

    /**
     * 从 target/test-classes/ 加载内部类的字节加载器.
     * EnumRewriter 依赖它读取枚举常量匿名类体(E$1.class 等).
     */
    private static java.util.function.Function<String, byte[]> testClassByteLoader() {
        Path testClassesDir = Paths.get("target/test-classes/");
        return internalName -> {
            try {
                Path innerFile = testClassesDir.resolve(internalName + ".class");
                if (Files.exists(innerFile)) {
                    return Files.readAllBytes(innerFile);
                }
            } catch (Exception ignored) {
            }
            return null;
        };
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
    public void testAnnotationUsagePointsAndNestedGenerics() throws Exception {
        // 字段/方法级注解使用点还原
        String src1 = decompileOnly("TestClass1");
        DecompileTestHarness.assertContains(src1,
                "@AnnotationDemo(value = \"field\", count = 1)",
                "@AnnotationDemo(value = \"testMethod\", count = 5)");
        String src2 = decompileOnly("TestClass2");
        DecompileTestHarness.assertContains(src2,
                "@AnnotationDemo(value = \"class\", count = 10)",
                // 参数级注解内联
                "public void annotatedMethod(@AnnotationDemo(\"param\") String param)");
        // 嵌套泛型局部变量(var map = new HashMap<String, List<Integer>>())
        DecompileTestHarness.assertContains(src2,
                "HashMap<String, List<Integer>> map = new HashMap()");
    }

    @Test
    public void testStringSwitchRoundTrip() throws Exception {
        // 两级字符串 switch(hashCode 分派 + 临时变量分派)必须还原为
        // 原生 switch (s) 形式,且输出可重新编译
        String out = harness.decompileResource("decompile-samples/m2-controlflow/StringSwitchSample.java");
        DecompileTestHarness.assertContains(out,
                "switch (s)",
                "case \"foo\":",
                "case \"bar\":");
        // 反编译输出必须可重新编译(差分基建发现的失败样例)
        DecompileTestHarness.assertNotContains(out, "hashCode()");
        Path tmp = Files.createTempDirectory("bdec-strswitch-");
        try {
            Path src = tmp.resolve("StringSwitchSample.java");
            Files.writeString(src, out, StandardCharsets.UTF_8);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            int rc = compiler.run(null, null, null, "-d", tmp.toString(), src.toString());
            org.junit.Assert.assertEquals("decompiled StringSwitchSample must recompile: "
                    + out, 0, rc);
        } finally {
            deleteRecursively(tmp.toFile());
        }
    }

    @Test
    public void testMultiNewArrayElementType() throws Exception {
        // MULTIANEWARRAY 必须保留元素类型:
        // new int[2][4] 不得降级为 new Object[2][4](IrBuilder 曾硬编码 Object)
        String out = harness.decompileSource("""
                class MultiNewArray {
                    int[][] grid() { return new int[2][4]; }
                    String[][] table() { return new String[3][5]; }
                    int[][][] cube() { return new int[2][3][4]; }
                    long[][] longs() { return new long[2][3]; }
                }
                """, "MultiNewArray");
        DecompileTestHarness.assertContains(out,
                "new int[2][4]",
                "new String[3][5]",
                "new int[2][3][4]",
                "new long[2][3]");
    }

    @Test
    public void testGenericSuperAndInterfaces() throws Exception {
        // 父类/接口的泛型类型参数必须从 Signature 重建(Base<String>,I<Integer>),
        // 而非退化为无泛型(extends Base,implements I).
        String out = harness.decompileSource("""
                class Base<T> {}
                interface I<T> {}
                class Foo extends Base<String> implements I<Integer> {}
                """, "Foo");
        DecompileTestHarness.assertContains(out,
                "extends Base<String>",
                "implements I<Integer>");
    }

    @Test
    public void testNestedClassModifiers() throws Exception {
        // 嵌套类的源码级修饰符位于外围类 InnerClasses 属性条目中
        // (嵌套类自身 class 文件的 access_flags 不含 ACC_STATIC/ACC_PRIVATE)
        String src = decompileOnly("TestClass2");
        DecompileTestHarness.assertContains(src,
                "public static class GenericClass<T, U>",
                "public static class StaticNested",
                "public class InnerClass");
    }

    @Test
    public void testNestedWildcardBound() throws Exception {
        // 通配符边界自身的泛型参数(? extends Comparable<String>)必须保留
        String out = harness.decompileSource("""
                import java.util.List;
                class WildBound {
                    public List<? extends Comparable<String>> m() { return null; }
                }
                """, "WildBound");
        DecompileTestHarness.assertContains(out,
                "List<? extends Comparable<String>>");
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
    public void testCycleTestRoundTrip() throws Exception {
        String r = decompileAndRecompile("CycleTest");
        if (!r.equals("OK")) {
            System.err.println("=== CycleTest decompile/compile failure ===");
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

    @Test
    public void testDynamicConstants() throws Exception {
        // 动态常量(condy):javac 不直接产生 CONSTANT_Dynamic,
        // 手工构造含 ConstantBootstraps 各标准引导方法的类字节码
        byte[] bytes = CondyBytecodeBuilder.buildCondyClass();
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        BdecResult result = engine.decompile("CondyHolder", bytes,
                new DecompileContext(config, n -> null));
        if (!result.success()) {
            throw new RuntimeException("Decompilation failed: " + result.cause());
        }
        DecompileTestHarness.assertContains(result.decompiledCode(),
                // getStaticFinal → 限定静态字段引用
                "return CondyHolder.VALUE;",
                // enumConstant → 枚举常量引用
                "return java.util.concurrent.TimeUnit.SECONDS;",
                // nullConstant → null
                "return null;",
                // primitiveClass → 类字面量
                "return int.class;");
    }

}
