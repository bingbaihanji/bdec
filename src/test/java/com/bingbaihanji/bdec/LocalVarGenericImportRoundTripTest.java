package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 局部变量声明类型的 import 感知渲染——
 * {@code List<String> x = null;} 此前输出全限定 {@code java.util.List<String>}
 * 且丢失 import,现已从变量表(含 LVTT genericType)收集 import 修复.
 * 覆盖常规类方法与枚举常量匿名体方法两条路径.
 */
public class LocalVarGenericImportRoundTripTest {

    // ============ 常规类方法 ============

    @Test
    public void testLocalVarNullAssignmentShortName() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "class C {\n"
                        + "    void m() {\n"
                        + "        List<String> x = null;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "List<String> x = null;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(output, "java.util.List<String> x");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testLocalVarNestedGenericShortName() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.Map;\n"
                        + "import java.util.List;\n"
                        + "class C {\n"
                        + "    void m() {\n"
                        + "        Map<String, List<Integer>> x = null;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "Map<String, List<Integer>> x = null;",
                "import java.util.Map;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(output, "java.util.Map<String");
        DecompileTestHarness.assertNotContains(output, "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    // ============ 枚举常量匿名体方法 ============

    @Test
    public void testEnumBodyLocalVarShortName() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "enum E {\n"
                        + "    A {\n"
                        + "        void m() {\n"
                        + "            List<String> x = null;\n"
                        + "        }\n"
                        + "    };\n"
                        + "    abstract void m();\n"
                        + "}\n",
                "E");
        DecompileTestHarness.assertContains(output,
                "List<String> x = null;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(output, "java.util.List<String> x");
        DecompileTestHarness.assertRecompiles(output, "E", Map.of());
    }

    @Test
    public void testEnumBodyLocalVarNestedGenericShortName() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.Map;\n"
                        + "import java.util.List;\n"
                        + "enum E {\n"
                        + "    A {\n"
                        + "        void m() {\n"
                        + "            Map<String, List<Integer>> x = null;\n"
                        + "        }\n"
                        + "    };\n"
                        + "    abstract void m();\n"
                        + "}\n",
                "E");
        DecompileTestHarness.assertContains(output,
                "Map<String, List<Integer>> x = null;",
                "import java.util.Map;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(output, "java.util.Map<String");
        DecompileTestHarness.assertNotContains(output, "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(output, "E", Map.of());
    }
}
