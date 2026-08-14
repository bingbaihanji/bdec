package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 通配符边界 import 感知渲染——
 * {@code ? extends List<Integer>} / {@code ? super List<Integer>} 的边界类型
 * 必须用短名(import 感知)而非全限定名渲染,与普通泛型实参一致.
 * 边界为 java.lang 类型(Number/String)时本就短名,故以非 java.lang 的
 * {@code List<Integer>} 作为边界,精确覆盖修复路径.
 */
public class WildcardBoundRoundTripTest {

    @Test
    public void testExtendsBoundField() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "class W {\n"
                        + "    Map<String, ? extends List<Integer>> c;\n"
                        + "}\n",
                "W");
        DecompileTestHarness.assertContains(output,
                "Map<String, ? extends List<Integer>> c;");
        DecompileTestHarness.assertNotContains(output, "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(output, "W", java.util.Map.of());
    }

    @Test
    public void testExtendsBoundMethodParam() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "class W {\n"
                        + "    void m(Map<String, ? extends List<Integer>> p) {}\n"
                        + "}\n",
                "W");
        DecompileTestHarness.assertContains(output,
                "void m(Map<String, ? extends List<Integer>> p)");
        DecompileTestHarness.assertNotContains(output, "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(output, "W", java.util.Map.of());
    }

    @Test
    public void testSuperBoundField() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "class W {\n"
                        + "    Map<String, ? super List<Integer>> c;\n"
                        + "}\n",
                "W");
        DecompileTestHarness.assertContains(output,
                "Map<String, ? super List<Integer>> c;");
        DecompileTestHarness.assertNotContains(output, "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(output, "W", java.util.Map.of());
    }
}
