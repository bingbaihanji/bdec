package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 合并 return 三元表达式的还原正确性.
 *
 * <p>{@code return b ? 1 : 2;} 被 javac 编译为<strong>单一</strong>{@code ireturn}
 * (true 分支 {@code goto} 汇入 return 块),两个分支值在 return 块处以 stack-PHI
 * 汇合.此前 PHI 解析在无分支上下文时取第一个操作数,导致 false 分支值丢失,
 * 输出 {@code if (!b) {} return 1;}(语义错误).现已还原为 {@code return b ? 1 : 2;}.</p>
 */
public class TernaryReturnRoundTripTest {

    @Test
    public void testIntTernaryReturn() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int m(boolean b) { return b ? 1 : 2; }\n"
                        + "}\n",
                "A");
        DecompileTestHarness.assertContains(output, "return b ? 1 : 2;");
        DecompileTestHarness.assertNotContains(output, "if (!b)");
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testStringTernaryReturn() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    String m(int x) { return x > 0 ? \"pos\" : \"neg\"; }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertContains(output, "\"pos\"");
        DecompileTestHarness.assertContains(output, "\"neg\"");
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }

    @Test
    public void testComplexExprTernaryReturn() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    int m(boolean b) { return b ? (1 + 2) : (3 - 4); }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }
}
