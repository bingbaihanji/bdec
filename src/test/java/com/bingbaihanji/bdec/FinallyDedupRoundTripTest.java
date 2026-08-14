package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * try-finally 的 finally 体去重正确性.
 *
 * <p>javac 将 finally 体在字节码中复制两份:一份内联在正常退出路径,
 * 一份在异常处理器中.反编译器应识别并剥离正常退出路径中的重复副本,
 * 使 finally 体仅出现一次.此前 {@code ComparisonUtils.expressionsEquivalent}
 * 仅处理调用/字面量/变量/字段访问,未覆盖赋值({@code x = 2})与自增
 * ({@code x++})表达式,导致 finally 体被重复执行(副作用执行两次,语义错误).</p>
 */
public class FinallyDedupRoundTripTest {

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    @Test
    public void testFinallyIncrementNotDuplicated() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int x = 0;\n"
                        + "    int m() { try { return 1; } finally { x++; } }\n"
                        + "}\n",
                "A");
        DecompileTestHarness.assertContains(output, "return 1;", "finally");
        // 自增 finally 体仅执行一次(不再重复内联进 try 体)
        assertEquals("x++ should appear exactly once (finally body only)", 1,
                countOccurrences(output, "this.x++;"));
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testFinallyAssignNotDuplicated() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    int x = 0;\n"
                        + "    void m() { try { x = 1; } finally { x = 2; } }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertContains(output, "this.x = 1;", "finally");
        // 赋值 finally 体仅出现一次(不再内联进 try 体造成 x=2 执行两次)
        assertEquals("x = 2 should appear exactly once (finally body only)", 1,
                countOccurrences(output, "this.x = 2;"));
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }

    @Test
    public void testFinallyMethodCallNotDuplicated() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    void unlock() {}\n"
                        + "    int m() { try { return 1; } finally { unlock(); } }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "return 1;", "finally");
        assertEquals("unlock() should appear exactly once (finally body only)", 1,
                countOccurrences(output, "this.unlock();"));
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }
}
