package com.bingbaihanji.bdec;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * 嵌套循环结构化:此前 ControlFlowStructurer 会把嵌套的两层循环
 * 折叠成一个扁平循环(内层条件/增量被扁平化丢失,外层增量错位),
 * 现在应还原为正确嵌套的两层 while 循环,而非一个扁平的循环体.
 */
public class NestedLoopRoundTripTest {

    @Test
    public void testNestedForLoop() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class NestedLoop {\n"
                        + "    static void go(int n) {\n"
                        + "        for (int i = 0; i < n; i++) {\n"
                        + "            for (int j = 0; j < n; j++) {\n"
                        + "                System.out.println(i + j);\n"
                        + "            }\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "NestedLoop");
        // 两层循环都应存在(而非被折叠成一个扁平循环)
        DecompileTestHarness.assertContains(output, "while (i < n)");
        DecompileTestHarness.assertContains(output, "while (j < n)");
        // 内层 while 必须嵌套在外层 while 内:外层 while → 内层 while → 外层 i++
        int outer = output.indexOf("while (i < n)");
        int inner = output.indexOf("while (j < n)");
        int outerInc = output.indexOf("i++;");
        assertTrue("inner while must appear after outer while", inner > outer);
        assertTrue("outer increment must appear after inner while", outerInc > inner);
        DecompileTestHarness.assertRecompiles(output, "NestedLoop", java.util.Map.of());
    }
}
