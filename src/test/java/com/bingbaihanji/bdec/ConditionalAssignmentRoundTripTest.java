package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 合并条件赋值(三元赋给局部变量)的还原正确性.
 *
 * <p>{@code int y = b ? 1 : 2;} 被 javac 编译为<strong>单一</strong>{@code istore}
 * (true 分支 {@code goto} 汇入 store 块),两个分支值在 store 块处以 stack-PHI 汇合.
 * 此前 PHI 解析在无分支上下文时取第一个操作数,导致 false 分支值丢失,
 * 输出 {@code if (!b) {} int y = 1;}(y 恒为 1,语义错误).现已还原为
 * {@code int y = b ? 1 : 2;}.</p>
 */
public class ConditionalAssignmentRoundTripTest {

    @Test
    public void testIntConditionalAssignment() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int m(boolean b) { int y = b ? 1 : 2; return y; }\n"
                        + "}\n",
                "A");
        DecompileTestHarness.assertContains(output, "int y = b ? 1 : 2;");
        DecompileTestHarness.assertNotContains(output, "int y = 1;");
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testConditionalAssignmentReused() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    int m(boolean b) { int y = b ? 1 : 2; return y * 10; }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertContains(output, "int y = b ? 1 : 2;");
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }

    @Test
    public void testStringConditionalAssignment() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    String m(int x) { String s = x > 0 ? \"pos\" : \"neg\"; return s; }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "\"pos\"");
        DecompileTestHarness.assertContains(output, "\"neg\"");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }
}
