package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 第 20 轮:普通类字段嵌套泛型的 import 收集缺口.
 *
 * <p>{@code AstBuilder.collectImport} 此前只处理顶层 CLASS 类型,
 * 不递归 typeArguments,也不处理 ARRAY/WILDCARD.字段渲染
 * ({@code StatementEmitter.typeName}) 已是 import 感知的短名,
 * 但 import 只收集了外层类型:{@code Map<String, List<Integer>> m;}
 * 输出 {@code import java.util.Map;} + 短名 {@code List},javac 重编译失败.</p>
 *
 * <p>本测试要求 collectImport 递归收集泛型实参,数组元素与通配符边界;
 * java.lang 直接成员与类型变量不产生 import(锁定不误收).</p>
 */
public class FieldGenericImportRoundTripTest {

    /** 核心缺口:字段嵌套泛型实参的 import 必须齐全,且输出可重编译. */
    @Test
    public void testFieldNestedGenericImportsCollectedAndRecompiles() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.Map;\n"
                        + "import java.util.List;\n"
                        + "class C { Map<String, List<Integer>> m; }\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "import java.util.Map;",
                "import java.util.List;",
                "Map<String, List<Integer>> m;");
        DecompileTestHarness.assertNotContains(output,
                "java.util.Map<String",
                "java.util.List<Integer>");
        // 修复前输出短名 List 但缺 import,javac 重编译报"找不到符号 List"
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    /** 多层嵌套:三个 import 全收. */
    @Test
    public void testDeeplyNestedGenericFieldImports() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.Map;\n"
                        + "import java.util.List;\n"
                        + "import java.util.Set;\n"
                        + "class C { Map<String, List<Set<Integer>>> m; }\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "import java.util.Map;",
                "import java.util.List;",
                "import java.util.Set;",
                "Map<String, List<Set<Integer>>> m;");
        DecompileTestHarness.assertNotContains(output,
                "java.util.Map<String",
                "java.util.List<Set",
                "java.util.Set<Integer>");
    }

    /** 通配符边界为 java.lang 直接成员:不产生 import(锁定不误收). */
    @Test
    public void testWildcardBoundJavaLangNoImport() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class C { List<? extends Number> l; }\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "import java.util.List;",
                "List<? extends Number> l;");
        DecompileTestHarness.assertNotContains(output,
                "import java.lang.Number;",
                "java.lang.Number");
    }

    /** 通配符边界为可导入类型:边界类型必须被递归收集. */
    @Test
    public void testWildcardBoundImported() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "import java.io.Serializable;\n"
                        + "class C { List<? extends Serializable> l; }\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "import java.util.List;",
                "import java.io.Serializable;");
        // 边界 import 收集是重新编译的前提(通配符边界文本由签名解析
        // 决定,输出以全限定名渲染亦可重编译)
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    /** 类级类型变量字段:不产生任何 import. */
    @Test
    public void testTypeVariableFieldNoImport() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource("class C<T> { T x; }\n", "C");
        DecompileTestHarness.assertContains(output,
                "class C<T>",
                "T x;");
        DecompileTestHarness.assertNotContains(output, "import ");
    }

    /** 数组元素类型必须递归收集(ARRAY 分支). */
    @Test
    public void testArrayElementGenericImportCollected() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class C { List<String>[] a; }\n",
                "C");
        DecompileTestHarness.assertContains(output, "import java.util.List;");
    }
}
