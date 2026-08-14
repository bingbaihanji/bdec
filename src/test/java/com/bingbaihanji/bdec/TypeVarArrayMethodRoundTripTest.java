package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 第 19 轮:方法签名覆盖闸门放行"数组元素为类型变量"的 ARRAY——
 * 裸 {@code T[]} 方法参数/返回不再渲染为擦除的 {@code Object[]}.
 *
 * <p>修复前 {@code static <T> T[] f(T[] a)} 与 {@code class C<T> { T[] get(T[] in) }}
 * 输出 {@code Object[]}(签名闸门对 ARRAY 全拒:internalName 恒 null,
 * typeArguments 恒空);嵌套 {@code List<T[]>} 已正常(typeArguments 非空),
 * 本测试同时锁定该回归.</p>
 */
public class TypeVarArrayMethodRoundTripTest {

    @Test
    public void testInstanceMethodTypeVarArray() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class C<T> {\n"
                        + "    T[] f(T[] a) { return a; }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "T[] f(T[] a)");
        DecompileTestHarness.assertNotContains(output, "Object[]");
    }

    @Test
    public void testStaticGenericMethodTypeVarArray() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class S {\n"
                        + "    static <T> T[] make(T[] a) { return a; }\n"
                        + "}\n",
                "S");
        DecompileTestHarness.assertContains(output, "<T> T[] make(T[] a)");
        DecompileTestHarness.assertNotContains(output, "Object[]");
    }

    @Test
    public void testNestedTypeVarArrayInGenericList() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class C<T> {\n"
                        + "    List<T[]> f(List<T[]> a) { return a; }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "List<T[]> f(List<T[]> a)");
        DecompileTestHarness.assertNotContains(output, "List<Object[]>");
    }
}
