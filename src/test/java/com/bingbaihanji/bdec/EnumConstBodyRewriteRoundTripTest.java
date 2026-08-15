package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 枚举常量体方法绕过标准重写链的回归测试.
 *
 * <p>根因:枚举常量体({@code enum E { A { ... } }})的方法经
 * {@code EnumRewriter.decompileInnerClassMethods} 反编译后直接渲染
 * ({@code emitSingleMethod}),跳过了常规编译单元路径上的 AST 重写链
 * (LambdaRewriter / ForEachRewriter / TernaryRewriter 等),导致
 * lambda 方法体不合并(占位符 lambda$ 注释残留)且合成
 * {@code lambda$xxx$N} 方法被单独输出;for-each 不重建为增强 for-each.</p>
 */
public class EnumConstBodyRewriteRoundTripTest {

    @Test
    public void testEnumBodyLambdaReconstructed() throws Exception {
        String src = "enum E {\n"
                + "    A {\n"
                + "        public String desc(Object o) {\n"
                + "            java.util.function.Supplier<String> s = () -> o.toString();\n"
                + "            return s.get();\n"
                + "        }\n"
                + "    };\n"
                + "    public abstract String desc(Object o);\n"
                + "}\n";
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "E");
        DecompileTestHarness.assertContains(out, "() ->");
        DecompileTestHarness.assertNotContains(out, "lambda$desc", "/* lambda");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }

    @Test
    public void testEnumBodyForEachReconstructed() throws Exception {
        String src = "enum E {\n"
                + "    A {\n"
                + "        public String desc() {\n"
                + "            int n = 0;\n"
                + "            for (String s : java.util.List.of(\"a\", \"b\")) {\n"
                + "                n += s.length();\n"
                + "            }\n"
                + "            return String.valueOf(n);\n"
                + "        }\n"
                + "    };\n"
                + "    public abstract String desc();\n"
                + "}\n";
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "E");
        // for-each 还原为增强 for 循环,不残留合成迭代器变量 varN
        DecompileTestHarness.assertContains(out, "for (String s :");
        DecompileTestHarness.assertNotContains(out, "var2", "var3", "hasNext");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }
}
