package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 第 20 轮:枚举常量匿名体方法内的嵌套泛型全限定名修复.
 *
 * <p>根因:{@code EnumRewriter.emitSingleMethod} 用空 imports 列表构造
 * {@code ExpressionEmitter},导致常量体方法内的类型(返回类型,参数,
 * 局部变量)失去 import 感知的短名渲染,输出
 * {@code java.util.Map<String, java.util.List<Integer>>} 这类全限定名
 * (可编译但观感差).</p>
 *
 * <p>项一 - 既有 imports 生效:外层枚举自身的签名已引用 Map/List,
 * AstBuilder 已收集对应 import;常量体方法渲染时必须使用同一 import 列表.</p>
 *
 * <p>项二 - 增量收集:常量体方法签名中使用了外层枚举完全未引用的类型,
 * EnumRewriter 应顺带把这些类型收集进编译单元的 import 列表.</p>
 */
public class EnumBodyGenericImportRoundTripTest {

    // ============ 项一:既有 imports 生效 ============

    /**
     * 外层枚举声明 {@code abstract Map<String, List<Integer>> get();},
     * 其签名使 AstBuilder 已收集 {@code java.util.Map}/{@code java.util.List} import.
     * 常量体覆写该方法的返回类型与局部变量类型必须渲染为短名,
     * 不得出现全限定名.
     */
    @Test
    public void testEnumBodyMethodExistingImportsShortNames() throws Exception {
        String src = "import java.util.Map;\n"
                + "import java.util.List;\n"
                + "enum E {\n"
                + "    A {\n"
                + "        public Map<String, List<Integer>> get() {\n"
                + "            Map<String, List<Integer>> m = null;\n"
                + "            return m;\n"
                + "        }\n"
                + "    };\n"
                + "    abstract Map<String, List<Integer>> get();\n"
                + "}\n";
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "E");
        DecompileTestHarness.assertContains(out,
                "public Map<String, List<Integer>> get()",
                "Map<String, List<Integer>> m = null;",
                "import java.util.Map;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(out,
                "java.util.Map<String",
                "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }

    // ============ 项二:增量收集常量体内新类型 ============

    /**
     * 常量体声明的方法(非覆写)签名使用 {@code Map<String, List<Integer>>},
     * 外层枚举自身完全未引用这些类型.EnumRewriter 必须收集这些 import
     * 并以短名渲染,输出可重新编译.
     */
    @Test
    public void testEnumBodyMethodNewTypesImportsCollected() throws Exception {
        String src = "import java.util.Map;\n"
                + "import java.util.List;\n"
                + "enum E {\n"
                + "    A {\n"
                + "        public Map<String, List<Integer>> get(List<Integer> in) { return null; }\n"
                + "    }\n"
                + "}\n";
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "E");
        DecompileTestHarness.assertContains(out,
                "public Map<String, List<Integer>> get(List<Integer> in)",
                "import java.util.Map;",
                "import java.util.List;");
        DecompileTestHarness.assertNotContains(out,
                "java.util.Map<String",
                "java.util.List<Integer>");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }

    // ============ 项三:常量体泛型方法的类型参数声明 ============

    /**
     * 常量体方法自带方法级类型参数({@code <T> T id(T x)}):
     * 泛型签名覆盖后必须连同 {@code <T>} 声明一起输出,
     * 否则类型变量引用无法编译(防御性分支).
     */
    @Test
    public void testEnumBodyGenericMethodTypeParamDeclared() throws Exception {
        String src = "enum E {\n"
                + "    A {\n"
                + "        public <T> T id(T x) { return x; }\n"
                + "    }\n"
                + "}\n";
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "E");
        DecompileTestHarness.assertContains(out,
                "public <T> T id(T x)");
        DecompileTestHarness.assertNotContains(out,
                "Object id(Object");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }
}
