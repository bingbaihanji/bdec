package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 第 18 轮:fromDescriptor 对类型变量数组元素("TT;" 描述符)的
 * CLASS 伪装消除——数组元素经 elementOf 重建时应产
 * kind=TYPE_VARIABLE,而非 CLASS + descriptor="TT;" 的伪装.
 *
 * <p>修复前字段 {@code T[] arr} 渲染为非法的 {@code TT;[] arr},
 * 修复后渲染为 {@code T[] arr}.</p>
 */
public class TypeVarArrayDescriptorRoundTripTest {

    @Test
    public void testTypeVariableArrayField() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class C<T> {\n"
                        + "    T[] arr;\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "T[] arr;");
        DecompileTestHarness.assertNotContains(output, "TT;", "import T;");
    }

    @Test
    public void testBoundedTypeVariableArrayField() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class C<T extends Number> {\n"
                        + "    T[] arr;\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "T[] arr;");
        DecompileTestHarness.assertNotContains(output, "TT;");
    }

    @Test
    public void testTypeVariableArrayInGenericField() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class C<T> {\n"
                        + "    List<T[]> lists;\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "List<T[]> lists;");
        DecompileTestHarness.assertNotContains(output, "TT;", "import T;");
    }

    @Test
    public void testTypeVariableArrayInGenericMethodParam() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class C {\n"
                        + "    static <T> void sort(List<T[]> l) {}\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "<T> void sort(List<T[]> l)");
        DecompileTestHarness.assertNotContains(output, "TT;", "import T;");
    }
}
