package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 第 17 轮:TypeKind.TYPE_VARIABLE 激活——签名解析的类型变量
 * 不再伪装为 CLASS(kind=TYPE_VARIABLE,descriptor 仍为 "T名字;",
 * displayName 仍为裸变量名),父类/接口实参与字段签名路径的
 * 类型变量不产生伪 import.
 */
public class TypeVariableKindRoundTripTest {

    @Test
    public void testTypeVariableAsSuperTypeArgument() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class Box<T> {}\n"
                        + "class C<T> extends Box<T> {}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "class C<T> extends Box<T>");
        DecompileTestHarness.assertNotContains(output, "import T;");
    }

    @Test
    public void testGenericMethodTypeVariable() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class C {\n"
                        + "    static <T> T id(T t) { return t; }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "<T> T id(T t)");
        DecompileTestHarness.assertNotContains(output, "import T;");
    }

    @Test
    public void testGenericFieldAndLocalVariable() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class C {\n"
                        + "    List<String> items;\n"
                        + "    void m() {\n"
                        + "        List<Integer> nums = java.util.Collections.emptyList();\n"
                        + "        System.out.println(nums);\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "List<String> items",
                "List<Integer> nums");
    }

    @Test
    public void testTypeVariableField() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class C<T> {\n"
                        + "    T item;\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "T item;");
        DecompileTestHarness.assertNotContains(output, "import T;");
    }

    @Test
    public void testTypeVariableNestedInMethodParameter() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class C {\n"
                        + "    static <T> void sort(List<T> l) {}\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "<T> void sort(List<T> l)");
        DecompileTestHarness.assertNotContains(output, "import T;");
    }
}
