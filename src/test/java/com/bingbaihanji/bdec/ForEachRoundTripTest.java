package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * P7:增强 for-each 重建——泛型集合的元素拆箱去糖化
 * {@code Iterator var2 = l.iterator(); while(var2.hasNext()){ int x = ((Integer)var2.next()).intValue(); ... }}
 * 应还原为 {@code for (int x : l) { ... }},不残留合成 Iterator 变量与 varN.
 */
public class ForEachRoundTripTest {

    @Test
    public void testGenericUnboxingElement() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "class ForEachInt {\n"
                        + "    static int sum(List<Integer> l) {\n"
                        + "        int s = 0;\n"
                        + "        for (int x : l) { s += x; }\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "ForEachInt");
        DecompileTestHarness.assertContains(output, "for (int x : l)");
        DecompileTestHarness.assertNotContains(output, "var");
        DecompileTestHarness.assertNotContains(output, "while");
        // 重建后 Iterator 用法消失,其 import 应被裁剪;List 仍被使用应保留
        DecompileTestHarness.assertNotContains(output, "import java.util.Iterator;");
        DecompileTestHarness.assertContains(output, "import java.util.List;");
        DecompileTestHarness.assertRecompiles(output, "ForEachInt", java.util.Map.of());
    }

    @Test
    public void testReferenceElement() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "class ForEachStr {\n"
                        + "    static void m(List<String> items) {\n"
                        + "        for (String item : items) { System.out.println(item); }\n"
                        + "    }\n"
                        + "}\n",
                "ForEachStr");
        DecompileTestHarness.assertContains(output, "for (String");
        DecompileTestHarness.assertNotContains(output, ".iterator()");
        DecompileTestHarness.assertNotContains(output, "while");
        DecompileTestHarness.assertNotContains(output, "import java.util.Iterator;");
        DecompileTestHarness.assertContains(output, "import java.util.List;");
        DecompileTestHarness.assertRecompiles(output, "ForEachStr", java.util.Map.of());
    }

    @Test
    public void testIntArray() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class ForEachIntArr {\n"
                        + "    static int sum(int[] a) {\n"
                        + "        int s = 0;\n"
                        + "        for (int x : a) { s += x; }\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "ForEachIntArr");
        DecompileTestHarness.assertContains(output, "for (int x : a)");
        DecompileTestHarness.assertNotContains(output, "var");
        DecompileTestHarness.assertNotContains(output, "while");
        DecompileTestHarness.assertRecompiles(output, "ForEachIntArr", java.util.Map.of());
    }

    @Test
    public void testReferenceArray() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class ForEachStrArr {\n"
                        + "    static void m(String[] names) {\n"
                        + "        for (String s : names) { System.out.println(s); }\n"
                        + "    }\n"
                        + "}\n",
                "ForEachStrArr");
        DecompileTestHarness.assertContains(output, "for (String s : names)");
        DecompileTestHarness.assertNotContains(output, "var");
        DecompileTestHarness.assertNotContains(output, "while");
        DecompileTestHarness.assertRecompiles(output, "ForEachStrArr", java.util.Map.of());
    }

    @Test
    public void testNestedIntArray() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class ForEachNestedArr {\n"
                        + "    static int go(int[][] m) {\n"
                        + "        int s = 0;\n"
                        + "        for (int[] row : m) {\n"
                        + "            for (int x : row) { s += x; }\n"
                        + "        }\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "ForEachNestedArr");
        // 外层与内层 for-each 都应重建;内层数组引用(前为 Object varN)
        // 必须还原为 int[] 组件类型,而非擦除为 Object.
        DecompileTestHarness.assertContains(output, "for (int[] row : m)");
        DecompileTestHarness.assertContains(output, "for (int x : row)");
        DecompileTestHarness.assertNotContains(output, "Object");
        DecompileTestHarness.assertNotContains(output, "var");
        DecompileTestHarness.assertNotContains(output, "while");
        DecompileTestHarness.assertRecompiles(output, "ForEachNestedArr", java.util.Map.of());
    }
}
