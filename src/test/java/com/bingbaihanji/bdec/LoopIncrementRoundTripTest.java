package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 循环携带变量的 IINC(++/--)语义保真:
 * <p>此前有两处缺陷会破坏 {@code for} 循环中的累加器与循环变量:
 * <ol>
 *   <li>{@code s += j} 被后置自增折叠成 {@code s = j++}(累加左操作数丢失);</li>
 *   <li>循环变量被内联成初始值,输出 {@code while (0 < n)} / {@code return 0},
 *       且 {@code int s = 0} 声明被错误移入循环体.</li>
 * </ol>
 */
public class LoopIncrementRoundTripTest {

    @Test
    public void testAccumulatorPlusEquals() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class AccAdd {\n"
                        + "    static int sum(int m) {\n"
                        + "        int s = 0;\n"
                        + "        for (int j = 0; j < m; j++) { s += j; }\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "AccAdd");
        // 累加语义必须保留(不得退化为 s = j++)
        DecompileTestHarness.assertContains(output, "s += j");
        DecompileTestHarness.assertContains(output, "while (j < m)");
        DecompileTestHarness.assertNotContains(output, "s = j++");
        DecompileTestHarness.assertRecompiles(output, "AccAdd", java.util.Map.of());
    }

    @Test
    public void testCountingLoop() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class AccInc {\n"
                        + "    static int go(int n) {\n"
                        + "        int s = 0;\n"
                        + "        for (int j = 0; j < n; j++) { s++; }\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "AccInc");
        // 循环条件与返回值必须保留变量名,不得内联成 0
        DecompileTestHarness.assertContains(output, "while (j < n)");
        DecompileTestHarness.assertContains(output, "s++");
        DecompileTestHarness.assertContains(output, "return s;");
        DecompileTestHarness.assertNotContains(output, "0 < n");
        DecompileTestHarness.assertNotContains(output, "return 0;");
        DecompileTestHarness.assertRecompiles(output, "AccInc", java.util.Map.of());
    }

    @Test
    public void testNestedAccumulator() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class NestedSum {\n"
                        + "    static int go(int n) {\n"
                        + "        int s = 0;\n"
                        + "        for (int i = 0; i < n; i++) {\n"
                        + "            for (int j = 0; j < n; j++) { s += j; }\n"
                        + "        }\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "NestedSum");
        // 外层与内层条件都要保留变量名(不得内联成 0)
        DecompileTestHarness.assertContains(output, "while (i < n)");
        DecompileTestHarness.assertContains(output, "while (j < n)");
        DecompileTestHarness.assertContains(output, "s += j");
        DecompileTestHarness.assertNotContains(output, "0 < n");
        DecompileTestHarness.assertRecompiles(output, "NestedSum", java.util.Map.of());
    }
}
