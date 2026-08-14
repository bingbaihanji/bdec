package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 多维数组 new 的维度补全与布尔字面量渲染正确性.
 * <ul>
 *   <li>{@code anewarray [I} 只携带最内层维度大小,此前渲染成 {@code new int[2]}
 *       (类型是 int[][] 却丢一维,无法重编译),现已补齐空括号为 {@code new int[2][]}.</li>
 *   <li>布尔字段赋值 {@code this.e = 1}(JVM 以 int 表示 boolean)现已渲染为
 *       {@code true/false},否则 int 常量不可赋给 boolean 字段无法重编译.</li>
 * </ul>
 */
public class NewArrayDimensionRoundTripTest {

    @Test
    public void testPartialDimensionNewArray() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int[][] x = new int[2][];\n"
                        + "    int[][] y = new int[2][3];\n"
                        + "    int[] z = new int[5];\n"
                        + "}\n",
                "A");
        DecompileTestHarness.assertContains(output, "new int[2][]");
        DecompileTestHarness.assertContains(output, "new int[2][3]");
        DecompileTestHarness.assertContains(output, "new int[5]");
        DecompileTestHarness.assertNotContains(output, "new int[2] =");
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testBooleanFieldLiteral() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    boolean a = true;\n"
                        + "    boolean b = false;\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertContains(output, "this.a = true;");
        DecompileTestHarness.assertContains(output, "this.b = false;");
        DecompileTestHarness.assertNotContains(output, "this.a = 1;");
        DecompileTestHarness.assertNotContains(output, "this.b = 0;");
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }
}
