package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 枚举常量体方法内指令级类型的 import 收集.
 *
 * <p>常量体方法({@code enum E { A { ... } }})仅出现在表达式中的类型——静态
 * 调用声明类({@code Collections.emptyList()}),NEW 结果类型,instanceof 目标,
 * 迭代器模式,INDY SAM 接口——不暴露于方法签名与局部变量表,此前未收集 import,
 * 输出短名却无 import 无法重编译.修复:EnumRewriter 复用
 * {@code AstBuilder.collectIrTypeImports} 扫描指令收集(与常规方法
 * {@code collectBodyImports} 同一约定).</p>
 */
public class EnumBodyImportRoundTripTest {

    @Test
    public void testStaticCallDeclaringClassImport() throws Exception {
        // 静态调用声明类:java.util.Collections 仅出现在常量体方法表达式中
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "enum E {\n"
                        + "    A {\n"
                        + "        public String desc() {\n"
                        + "            return java.util.Collections.emptyList().toString();\n"
                        + "        }\n"
                        + "    };\n"
                        + "    public abstract String desc();\n"
                        + "}\n",
                "E");
        DecompileTestHarness.assertContains(out,
                "import java.util.Collections;",
                "Collections.emptyList()");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }

    @Test
    public void testNewOnlyTypeImport() throws Exception {
        // NEW 结果类型:LinkedHashSet 仅出现在 new 表达式中
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "enum E {\n"
                        + "    A {\n"
                        + "        public String desc() {\n"
                        + "            java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<String>();\n"
                        + "            return s.toString();\n"
                        + "        }\n"
                        + "    };\n"
                        + "    public abstract String desc();\n"
                        + "}\n",
                "E");
        DecompileTestHarness.assertContains(out, "import java.util.LinkedHashSet;");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }

    @Test
    public void testInstanceofAndLambdaSamImport() throws Exception {
        // instanceof 目标 + INDY SAM 函数式接口(Supplier)
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.function.Supplier;\n"
                        + "enum E {\n"
                        + "    A {\n"
                        + "        public String desc(Object o) {\n"
                        + "            boolean b = o instanceof java.util.ArrayList;\n"
                        + "            Supplier<String> s = () -> b ? \"y\" : \"n\";\n"
                        + "            return s.get();\n"
                        + "        }\n"
                        + "    };\n"
                        + "    public abstract String desc(Object o);\n"
                        + "}\n",
                "E");
        DecompileTestHarness.assertContains(out,
                "import java.util.ArrayList;",
                "import java.util.function.Supplier;");
    }

    @Test
    public void testIteratorPatternImport() throws Exception {
        // for-each 已在常量体重建(不再产生 Iterator),故 Iterator import 应被 prune.
        // 此前常量体 for-each 未重建,残留 import java.util.Iterator; 是 buggy 输出
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "enum E {\n"
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
                        + "}\n",
                "E");
        DecompileTestHarness.assertContains(out, "for (String s :");
        DecompileTestHarness.assertNotContains(out, "import java.util.Iterator;");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }
}
