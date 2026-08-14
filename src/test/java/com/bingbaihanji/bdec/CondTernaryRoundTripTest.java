package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 三元表达式作为其它表达式的操作数时的还原正确性.
 *
 * <p>此前两处静默语义错误:{@code (a>0?b:c)>0 ? d : e} 曾反编译为
 * {@code return b <= 0 ? e : d;}(条件直接取首操作数 {@code b},丢内层三元
 * 的 {@code c});{@code s += a[i]>0 ? 1 : 0} 曾反编译为无条件 {@code s++}
 * (恒加一).根因:被无条件消费(stack-PHI 作为 CONDITION/BINARY 操作数)
 * 的三元 PHI 在无分支上下文时按首操作数解析,丢 false 分支值.现按 CFG
 * 菱形结构(汇合块的直接支配者即条件块)重建 {@code cond ? a : b}.</p>
 */
public class CondTernaryRoundTripTest {

    @Test
    public void testTernaryInConditionPosition() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int m(int a, int b, int c, int d, int e) {\n"
                        + "        return (a > 0 ? b : c) > 0 ? d : e;\n"
                        + "    }\n"
                        + "}\n",
                "A");
        // 条件须保留内层三元(引用 c),不得退化为直接取 b
        DecompileTestHarness.assertNotContains(output, "b <= 0 ? e : d");
        DecompileTestHarness.assertContains(output, "? c : b");
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testTernaryInConditionPositionUnparenthesized() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    int m(int a, int b, int c, int d, int e) {\n"
                        + "        return (a > 0 ? b : c) > 0 ? d : e;\n"
                        + "    }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertNotContains(output, "b <= 0 ? e : d");
        DecompileTestHarness.assertContains(output, "? c : b");
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }

    @Test
    public void testTernaryCompoundAssignmentInLoop() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    int m(int[] arr) {\n"
                        + "        int s = 0;\n"
                        + "        for (int i = 0; i < arr.length; i++) {\n"
                        + "            s += arr[i] > 0 ? 1 : 0;\n"
                        + "        }\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        // 不得退化为无条件自增(恒加一)
        DecompileTestHarness.assertNotContains(output, "s++;");
        DecompileTestHarness.assertContains(output, "s += arr[i] <= 0 ? 0 : 1");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }
}
