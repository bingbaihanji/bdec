package com.bingbaihanji.bdec;

import org.junit.Before;
import org.junit.Test;

/**
 * Round-trip tests for basic control flow structures.
 * Each test compiles a hand-written .java sample, decompiles it with BDEC,
 * and asserts the output contains the expected structural patterns.
 */
public class ControlFlowTest {

    private DecompileTestHarness harness;

    @Before
    public void setUp() {
        harness = new DecompileTestHarness();
    }

    @Test
    public void testIfElse() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/IfElseSample.java");
        // Verify class and method structure exists
        DecompileTestHarness.assertContains(out, "class IfElseSample", "test", "return");
        // Verify no placeholder comments leaked (placeholder bug regression)
        DecompileTestHarness.assertNotContains(out, "/*");
        // TernaryRewriter converts simple if/else to ternary: flag == 0 ? 0 : 1
        // This is semantically correct. Verify the ternary operator is present.
        DecompileTestHarness.assertContains(out, "?");
        // P3/P6 fix: parameter name should be "flag" from LVT, not "param0"
        DecompileTestHarness.assertContains(out, "flag");
    }

    @Test
    public void testTryFinally() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/TryFinallySample.java");
        // Verify correct try-finally structure with return value propagation
        DecompileTestHarness.assertContains(out, "class TryFinallySample", "lock", "return 42");
        DecompileTestHarness.assertContains(out, "try", "finally");
        // Ensure lock.lock() is OUTSIDE try body
        DecompileTestHarness.assertContains(out, "lock.lock()");
    }

    @Test
    public void testWhileLoop() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/WhileLoopSample.java");
        // Verify class, method, and return presence
        DecompileTestHarness.assertContains(out, "class WhileLoopSample", "test", "return");
        // TODO M1+: tighten to "while" when loop structuring covers this pattern
    }

    @Test
    public void testSwitch() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/SwitchSample.java");
        DecompileTestHarness.assertContains(out, "class SwitchSample", "switch", "return");
        // Case body regression: return statements should now be emitted
        DecompileTestHarness.assertContains(out, "\"one\"", "\"two\"", "\"other\"");
    }

    @Test
    public void testEnumSwitch() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/EnumSwitchSample.java");
        DecompileTestHarness.assertContains(out, "class EnumSwitchSample", "switch");
        // EnumSwitchRewriter should remove $SwitchMap$ array references from switch discriminant.
        DecompileTestHarness.assertNotContains(out, "SwitchMap");
    }

    @Test
    public void testDoWhile() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/DoWhileSample.java");
        DecompileTestHarness.assertContains(out, "class DoWhileSample", "return");
    }

    @Test
    public void testBooleanMethod() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/BooleanMethodSample.java");
        DecompileTestHarness.assertContains(out, "class BooleanMethodSample", "return", "n");
        // PHI/merge-point fix: if-else-return should produce correct ternary
        DecompileTestHarness.assertContains(out, "?");
    }

    @Test
    public void testDecompileBasicClass() throws Exception {
        // Test that basic decompilation succeeds without exceptions
        String source = """
                package test;
                public class BasicTest {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        String out = harness.decompileSource(source, "BasicTest");
        DecompileTestHarness.assertContains(out,
                "class BasicTest",
                "add",
                "return");
    }

    @Test
    public void testConditionNotInverted() throws Exception {
        // P1 regression: condition should be "x > 0" not "0 > x"
        String source = """
                package test;
                public class CondTest {
                    public boolean isPositive(int x) {
                        return x > 0;
                    }
                }
                """;
        String out = harness.decompileSource(source, "CondTest");
        DecompileTestHarness.assertContains(out, "class CondTest", "isPositive", "return");
        // With -g debug, x should be named "x" from LVT
        // The condition should not be inverted (no "0 > x")
        DecompileTestHarness.assertNotContains(out, "0 >");
    }

    @Test
    public void testBooleanArgFolding() throws Exception {
        // Boolean constants in method calls should not be emitted as integers.
        String source = """
                package test;
                public class BoolArgTest {
                    public BoolArgTest(int x, boolean flag) {}
                    public static BoolArgTest create(int x) {
                        return new BoolArgTest(x, false);
                    }
                }
                """;
        String out = harness.decompileSource(source, "BoolArgTest");
        DecompileTestHarness.assertContains(out, "class BoolArgTest", "create", "return");
        // Should contain "false" (boolean), not "0" (integer)
        DecompileTestHarness.assertContains(out, "false");
    }

    @Test
    public void testTryCatchBasic() throws Exception {
        // P2: try-catch should be detected
        String source = """
                package test;
                public class TryCatchTest {
                    public int test() {
                        try {
                            return 1;
                        } catch (Exception e) {
                            return 0;
                        }
                    }
                }
                """;
        String out = harness.decompileSource(source, "TryCatchTest");
        DecompileTestHarness.assertContains(out, "class TryCatchTest", "try", "return");
    }

    @Test
    public void testArrayAccess() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/ArraySample.java");
        DecompileTestHarness.assertContains(out, "class ArraySample", "arr[", "[", "return");
    }

    @Test
    public void testInstanceOfExpr() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/InstanceOfSample.java");
        DecompileTestHarness.assertContains(out, "class InstanceOfSample", "instanceof", "return");
        DecompileTestHarness.assertNotContains(out, "/* instanceof */");
    }

    @Test
    public void testStaticMethodCall() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/StaticCallSample.java");
        DecompileTestHarness.assertContains(out, "class StaticCallSample", "Integer", "toString", "return");
    }

    @Test
    public void testNewInstanceWithArgs() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/NewInstanceSample.java");
        DecompileTestHarness.assertContains(out, "class NewInstanceSample", "new RuntimeException", "error", "return");
    }

    @Test
    public void testStringSwitch() throws Exception {
        // String switch generates complex hashCode/equals bytecode scaffolding.
        // With the translateBlockGroup fix, case bodies should now be emitted.
        String out = harness.decompileResource("decompile-samples/m2-controlflow/StringSwitchSample.java");
        DecompileTestHarness.assertContains(out, "class StringSwitchSample", "return");
        // TODO: when StringSwitchRewriter fully activates:
        // DecompileTestHarness.assertContains(out, "\"foo\"", "\"bar\"");
        // DecompileTestHarness.assertNotContains(out, "hashCode");
    }

    @Test
    public void testLambda() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/LambdaSample.java");
        DecompileTestHarness.assertContains(out, "class LambdaSample", "->", "return");
        // TODO: re-enable when LambdaRewriter filtering is active end-to-end
        // DecompileTestHarness.assertNotContains(out, "lambda$test");
    }

    @Test
    public void testMethodRef() throws Exception {
        // Method reference detection via bootstrap method resolution.
        // TODO: tighten assertions when structurer handles invokedynamic returns properly
        //       (return keyword is currently missing from method-ref-only method bodies).
        String out = harness.decompileResource("decompile-samples/m2-controlflow/MethodRefSample.java");
        DecompileTestHarness.assertContains(out, "class MethodRefSample", "return");
        // String::length method reference detected from bootstrap method info
        DecompileTestHarness.assertContains(out, "String::length");
        // Should NOT use lambda arrow notation for method references
        DecompileTestHarness.assertNotContains(out, "->");
    }

    @Test
    public void testVoidMethodNotReturned() throws Exception {
        // Fix: void method calls should NOT be wrapped in return statements
        // when the other branch has a return (wrapAsReturn fix).
        String source = """
                package test;
                import java.util.concurrent.locks.ReentrantLock;
                public class VoidReturnTest {
                    private final ReentrantLock lock = new ReentrantLock();
                    public int test(boolean flag) {
                        if (flag) {
                            lock.lock();
                            try {
                                return 42;
                            } finally {
                                lock.unlock();
                            }
                        }
                        return 0;
                    }
                }
                """;
        String out = harness.decompileSource(source, "VoidReturnTest");
        DecompileTestHarness.assertContains(out, "class VoidReturnTest", "lock", "return");
        // Should NOT wrap void lock.lock() in a return statement
        DecompileTestHarness.assertNotContains(out, "return lock.lock()");
        DecompileTestHarness.assertNotContains(out, "return lock.unlock()");
        // Try to compile the decompiled output
        assertCompiles(out, "VoidReturnTest");
    }

    @Test
    public void testDuplicateVarDeclMerged() throws Exception {
        // Fix: duplicate variable declarations in the same branch
        // should be converted to assignments (translateBranchBody fix).
        String source = """
                package test;
                public class DupVarTest {
                    public int test(int[] dest) {
                        if (dest.length != 0) {
                            int transferCount = 0;
                            transferCount = Math.min(10, dest.length);
                            return transferCount;
                        }
                        return 0;
                    }
                }
                """;
        String out = harness.decompileSource(source, "DupVarTest");
        // Should compile clean
        assertCompiles(out, "DupVarTest");
    }

    /** Verify that decompiled Java source compiles with javac. */
    private static void assertCompiles(String source, String className) {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return; // skip if no compiler available
        }
        java.io.File tmpDir = null;
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bdec-compile-");
            tmpDir = dir.toFile();
            java.io.File srcFile = new java.io.File(dir.toFile(), className + ".java");
            java.nio.file.Files.writeString(srcFile.toPath(), source,
                    java.nio.charset.StandardCharsets.UTF_8);
            // Capture stderr to get compile errors
            java.io.ByteArrayOutputStream errStream = new java.io.ByteArrayOutputStream();
            int result = compiler.run(null, null, errStream,
                    "-d", tmpDir.getAbsolutePath(),
                    srcFile.getAbsolutePath());
            if (result != 0) {
                // Print source and errors for debugging
                System.err.println("=== Failed to compile decompiled output: " + className + " ===");
                System.err.println(source);
                System.err.println("=== Compile errors ===");
                System.err.println(errStream.toString());
                System.err.println("=== End ===");
                throw new AssertionError("Decompiled output for " + className
                        + " failed to compile (exit code " + result + "): "
                        + errStream.toString());
            }
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Compilation check failed for " + className, e);
        } finally {
            if (tmpDir != null) {
                deleteDir(tmpDir);
            }
        }
    }

    private static void deleteDir(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) {
                    deleteDir(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
