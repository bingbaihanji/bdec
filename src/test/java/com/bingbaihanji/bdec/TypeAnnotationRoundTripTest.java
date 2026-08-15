package com.bingbaihanji.bdec;

import org.junit.Before;
import org.junit.Test;

/**
 * JSR-308 类型注解(type annotation)往返测试.
 *
 * <p>从 {@link BytecodeTestRoundTripTest} 拆分:类/方法/字段/局部变量的
 * 类型注解使用点还原,涵盖 TYPE_USE 在类类型参数,父类型,cast/new/instanceof,
 * 数组维度与枚举常量上的落点.</p>
 */
public class TypeAnnotationRoundTripTest {

    private DecompileTestHarness harness;

    @Before
    public void setUp() {
        harness = new DecompileTestHarness();
    }

    @Test
    public void testClassLevelTypeAnnotations() throws Exception {
        // 类/方法类型参数声明(0x00/0x01)与父类型(0x10)注解
        String out = harness.decompileSource("""
                                             import java.lang.annotation.*;
                                             @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                                             @interface A {}
                                             class Base {}
                                             class TypeParamAnnot<@A T> extends @A Base {
                                                 <@A U> U m(U u) { return u; }
                                             }
                                             """, "TypeParamAnnot");
        DecompileTestHarness.assertContains(out,
                "class TypeParamAnnot<@A T> extends @A Base",
                "<@A U> U m(U u)");
    }

    @Test
    public void testLocalVariableTypeAnnotations() throws Exception {
        // JSR-308 0x40:局部变量声明上的类型注解
        String out = harness.decompileSource("""
                                             import java.lang.annotation.*;
                                             @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                                             @interface A {}
                                             class LocalVarAnnot {
                                                 void m() {
                                                     @A String x = "hi";
                                                     System.out.println(x);
                                                 }
                                             }
                                             """, "LocalVarAnnot");
        DecompileTestHarness.assertContains(out, "@A String x = \"hi\"");
    }

    @Test
    public void testCastNewInstanceofTypeAnnotations() throws Exception {
        // JSR-308 0x43/0x44/0x47:cast/new/instanceof 指令偏移量处的类型注解
        // (checkcast/new/instanceof 的 offset 定位,非类型路径定位)
        String out = harness.decompileSource("""
                                             import java.lang.annotation.*;
                                             @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                                             @interface A {}
                                             class CastNewAnnot {
                                                 String cast(Object o) { return (@A String) o; }
                                                 Object create() { return new @A StringBuilder(); }
                                                 boolean check(Object o) { return o instanceof @A String; }
                                                 int[] arr() { return new @A int[3]; }
                                                 Object[] arr2() { return new Object @A [2]; }
                                             }
                                             """, "CastNewAnnot");
        DecompileTestHarness.assertContains(out,
                "(@A String)",
                "new @A StringBuilder()",
                "instanceof @A String",
                "new @A int[3]",
                "new Object @A [2]");
    }

    @Test
    public void testEnumConstantTypeUseAnnotation() throws Exception {
        // TYPE_USE-only 注解落在字段的 RuntimeVisibleTypeAnnotations(0x13 空路径),
        // 而非 RuntimeVisibleAnnotations.枚举常量必须内联输出 "@A RED".
        String out = harness.decompileSource("""
                                             import java.lang.annotation.*;
                                             @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                                             @interface A {}
                                             enum Color {
                                                 @A RED, GREEN, @A BLUE
                                             }
                                             """, "Color");
        DecompileTestHarness.assertContains(out, "@A RED", "@A BLUE");
    }

    @Test
    public void testTypeAnnotations() throws Exception {
        // JSR-308 类型注解:字段/参数/返回类型,泛型参数位置,数组元素与维度
        String out = harness.decompileSource("""
                                             import java.lang.annotation.*;
                                             import java.util.*;
                                             @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                                             @interface NonNull {}
                                             class TypeAnnot {
                                                 @NonNull String f;
                                                 List<@NonNull String> list;
                                                 String @NonNull [] arr;
                                                 @NonNull String arr2[];
                                                 Map<String, @NonNull List<@NonNull Integer>> nested(@NonNull String p) { return null; }
                                             }
                                             """, "TypeAnnot");
        DecompileTestHarness.assertContains(out,
                "@NonNull String f",
                "List<@NonNull String> list",
                "String @NonNull [] arr",
                "@NonNull String[] arr2",
                "Map<String, @NonNull List<@NonNull Integer>> nested(@NonNull String p)");
    }
}
