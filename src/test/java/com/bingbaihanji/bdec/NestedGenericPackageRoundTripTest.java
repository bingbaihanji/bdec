package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第 19 轮:重写器文本拼接路径的嵌套泛型包名 import 简化.
 *
 * <p>{@code ExpressionEmitter.typeName()} 已有 import 感知的短名渲染,但三个重写器
 * 拼接的文本仍走 {@code JavaType.displayName()}(全限定):</p>
 * <ul>
 *   <li>RecordRewriter — record 组件列表(record R(Box&lt;Map&lt;...&gt;&gt; b))</li>
 *   <li>RecordPatternRewriter — record 模式变量类型(instanceof R(Box&lt;...&gt; b, ...))</li>
 *   <li>EnumRewriter — 枚举常规字段类型的嵌套泛型实参缺少 import 收集
 *       (Map&lt;String, List&lt;Integer&gt;&gt; 只收集 java.util.Map,输出无法重新编译)</li>
 * </ul>
 *
 * <p>统一通过 {@code util.TypeText} 渲染短名并收集 import,所有用例均附带
 * 重新编译断言(输出必须能被 javac 再编译).</p>
 */
public class NestedGenericPackageRoundTripTest {

    // ============ RecordRewriter:record 组件 ============

    @Test
    public void testRecordComponentNestedGenericShortNames() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "class Box<T> {}\n"
                        + "record R(Box<Map<String, List<Integer>>> b) {}\n",
                "R");
        DecompileTestHarness.assertContains(output,
                "record R(Box<Map<String, List<Integer>>> b)",
                "import java.util.Map;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(output,
                "java.util.Map<String",
                "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(output, "R",
                Map.of("Box", "class Box<T> {}"));
    }

    @Test
    public void testRecordComponentSamePackageNoImport() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "package p;\n"
                        + "import java.util.Map;\n"
                        + "class Box<T> {}\n"
                        + "record R(Box<Map<String, Integer>> b) {}\n",
                "R");
        DecompileTestHarness.assertContains(output,
                "package p;",
                "record R(Box<Map<String, Integer>> b)",
                "import java.util.Map;");
        // 同包类型 Box 不得产生 import(与 AstBuilder 的同包 import 过滤一致)
        DecompileTestHarness.assertNotContains(output,
                "import p.Box;",
                "java.util.Map<String");
        DecompileTestHarness.assertRecompiles(output, "R",
                Map.of("Box", "package p;\nclass Box<T> {}"));
    }

    // ============ RecordPatternRewriter:record 模式变量类型 ============

    @Test
    public void testRecordPatternComponentNestedGenericShortNames() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.Map;\n"
                        + "class Box<T> {}\n"
                        + "record R(Box<Map<String, Integer>> b, int n) {}\n"
                        + "class Use {\n"
                        + "  int test(Object obj) {\n"
                        + "    if (obj instanceof R(Box<Map<String, Integer>> b, int n)) {\n"
                        + "      System.out.println(b);\n"
                        + "      System.out.println(n);\n"
                        + "    }\n"
                        + "    return 7;\n"
                        + "  }\n"
                        + "}\n",
                "Use");
        DecompileTestHarness.assertContains(output,
                "instanceof R(Box<Map<String, Integer>> b, int n)",
                "import java.util.Map;");
        DecompileTestHarness.assertNotContains(output,
                "java.util.Map<String");
        Map<String, String> companions = new LinkedHashMap<>();
        companions.put("Box", "class Box<T> {}");
        companions.put("R", "import java.util.Map;\n"
                + "record R(Box<Map<String, Integer>> b, int n) {}");
        DecompileTestHarness.assertRecompiles(output, "Use", companions);
    }

    // ============ EnumRewriter:枚举字段嵌套泛型 import ============

    @Test
    public void testEnumFieldNestedGenericImports() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "enum E { X; private Map<String, List<Integer>> m; }\n",
                "E");
        DecompileTestHarness.assertContains(output,
                "enum E",
                "private Map<String, List<Integer>> m;",
                "import java.util.Map;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(output,
                "java.util.Map<String",
                "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(output, "E", Map.of());
    }
}
