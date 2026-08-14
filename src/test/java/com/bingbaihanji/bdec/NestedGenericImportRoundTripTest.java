package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 第 16 轮:父类/接口嵌套泛型实参的 import 简化.
 *
 * <p>父类/接口的泛型实参此前用 {@code displayName()} 渲染
 * (剥 java.lang,其余全限定),输出 {@code Box<java.util.Map<String,
 * java.util.List<Integer>>>}.字段声明则用短名 + import 收集约定.
 * 本测试要求实参沿用字段约定:输出 {@code Box<Map<String,
 * List<Integer>>>} 并收集 {@code import java.util.Map;} 与
 * {@code import java.util.List;}.</p>
 */
public class NestedGenericImportRoundTripTest {

    @Test
    public void testNestedGenericArgumentsUseShortNames() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "class Box<T> {}\n"
                        + "class C extends Box<Map<String, List<Integer>>> {}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "extends Box<Map<String, List<Integer>>>",
                "import java.util.Map;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(output,
                "java.util.Map<String");
    }

    @Test
    public void testNestedGenericArgumentsOnInterface() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "interface Box<T> {}\n"
                        + "class C implements Box<Map<String, List<Integer>>> {}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "implements Box<Map<String, List<Integer>>>",
                "import java.util.Map;",
                "import java.util.List;");
    }
}
