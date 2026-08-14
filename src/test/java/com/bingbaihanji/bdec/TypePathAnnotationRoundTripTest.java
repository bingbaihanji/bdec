package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 第 16 轮:父类/接口泛型实参上的 TYPE_USE 注解(0x10 + type_path TYPE_ARGUMENT).
 *
 * <p>javac 将 {@code class C extends Base<@A String> implements I<@A Integer>}
 * 中的 {@code @A} 编码为 RuntimeVisibleTypeAnnotations 条目:
 * target_type = 0x10(CLASS_EXTENDS),target_info = [supertype_index]
 * (65535 = 父类,否则 = 接口下标),type_path = [TYPE_ARGUMENT(0)].
 * 此前 AstBuilder 的 0x10 收集循环只处理 type_path 为空的注解,
 * 带 TYPE_ARGUMENT 路径的注解被静默丢弃.</p>
 *
 * <p>注意:@A 必须声明 RUNTIME retention——BDEC 只读取
 * RuntimeVisibleTypeAnnotations,默认 CLASS retention 的注解落在
 * RuntimeInvisibleTypeAnnotations 中,无法在反编译输出中还原.</p>
 */
public class TypePathAnnotationRoundTripTest {

    @Test
    public void testSuperAndInterfaceTypeArgumentAnnotations() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.lang.annotation.*;\n"
                        + "@Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) @interface A {}\n"
                        + "class Base<T> {}\n"
                        + "interface I<T> {}\n"
                        + "class C extends Base<@A String> implements I<@A Integer> {}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "extends Base<@A String>",
                "implements I<@A Integer>");
    }

    @Test
    public void testNestedTypeArgumentAnnotation() throws Exception {
        // 多级路径 [TYPE_ARGUMENT(0), TYPE_ARGUMENT(0)]:
        // Base<Map<@A String, Integer>> 中 @A 位于外层实参 Map 的第 0 个实参上
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.lang.annotation.*;\n"
                        + "import java.util.Map;\n"
                        + "@Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) @interface A {}\n"
                        + "class Base<T> {}\n"
                        + "class C extends Base<Map<@A String, Integer>> {}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "extends Base<Map<@A String, Integer>>",
                "import java.util.Map;");
    }
}
