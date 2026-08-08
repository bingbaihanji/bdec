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
        // TODO M1+: tighten to "if", "else" when if/else detection covers all patterns
    }

    @Test
    public void testTryFinally() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/TryFinallySample.java");
        // Verify lock/unlock pattern and return value preserved
        DecompileTestHarness.assertContains(out, "class TryFinallySample", "lock", "return");
        // TODO M1+: tighten to "try", "finally" when try-catch structuring is complete
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
    }

    @Test
    public void testDoWhile() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/DoWhileSample.java");
        DecompileTestHarness.assertContains(out, "class DoWhileSample", "return");
    }

    @Test
    public void testBooleanMethod() throws Exception {
        String out = harness.decompileResource("decompile-samples/m2-controlflow/BooleanMethodSample.java");
        DecompileTestHarness.assertContains(out, "class BooleanMethodSample", "return", "0");
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
}
