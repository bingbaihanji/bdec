package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 嵌套三元表达式的还原正确性.
 *
 * <p>{@code return a > 0 ? (b > 0 ? c : d) : e;} 被 javac 编译为单一
 * {@code ireturn},所有分支值在 return 块处以 stack-PHI 汇合,内层三元
 * 作为外层三元的某个操作数出现.此前 PHI 解析在无分支上下文时一律取
 * 首操作数,导致输出恒返回 {@code c}(丢 {@code d},{@code e},语义错误).
 * 现按 CFG 前驱顺序将分支块与 PHI 操作数对齐,递归还原为嵌套三元.</p>
 */
public class NestedTernaryRoundTripTest {

    @Test
    public void testNestedTernaryInTrueBranch() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int m(int a, int b, int c, int d, int e) {\n"
                        + "        return a > 0 ? (b > 0 ? c : d) : e;\n"
                        + "    }\n"
                        + "}\n",
                "A");
        DecompileTestHarness.assertNotContains(output, "return c;");
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testNestedTernaryInFalseBranch() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    int m(int a, int b, int c, int d, int e) {\n"
                        + "        return a > 0 ? c : (b > 0 ? d : e);\n"
                        + "    }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }

    @Test
    public void testTripleNestedTernary() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    int m(int a, int b, int c, int x, int y, int z, int w) {\n"
                        + "        return a > 0 ? (b > 0 ? (c > 0 ? x : y) : z) : w;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testNestedTernaryAssignment() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class D {\n"
                        + "    int m(int a, int b, int c, int d, int e) {\n"
                        + "        int y = a > 0 ? (b > 0 ? c : d) : e;\n"
                        + "        return y;\n"
                        + "    }\n"
                        + "}\n",
                "D");
        DecompileTestHarness.assertNotContains(output, "= c;");
        DecompileTestHarness.assertRecompiles(output, "D", Map.of());
    }
}
