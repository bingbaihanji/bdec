package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * Round16:方法引用类型实参注解的往返测试.
 *
 * <p>覆盖 JVMS 类型注解目标
 * 0x4B METHOD_REFERENCE_TYPE_ARGUMENT({@code C::<@A String>id})与
 * 0x4A CONSTRUCTOR_REFERENCE_TYPE_ARGUMENT({@code C::<@A C>new}).
 */
public class MethodRefAnnotationRoundTripTest {

    private static final String SOURCE = """
                                         import java.lang.annotation.ElementType;
                                         import java.lang.annotation.Retention;
                                         import java.lang.annotation.RetentionPolicy;
                                         import java.lang.annotation.Target;
                                         import java.util.function.Function;
                                         import java.util.function.Supplier;
                                         
                                         @Retention(RetentionPolicy.RUNTIME)
                                         @Target(ElementType.TYPE_USE)
                                         @interface A {
                                         }
                                         
                                         class C {
                                             static <T> T id(T t) {
                                                 return t;
                                             }
                                         
                                             static <A2, B2> A2 m2(B2 b) {
                                                 return null;
                                             }
                                         
                                             void m() {
                                                 Function<String, String> f = C::<@A String>id;
                                                 Function<Integer, String> g = C::<String, @A Integer>m2;
                                                 Supplier<C> s = C::<@A C>new;
                                             }
                                         }
                                         """;

    @Test
    public void testMethodRefTypeArgumentAnnotation() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(SOURCE, "C");
        DecompileTestHarness.assertContains(output, "C::<@A String>id");
        DecompileTestHarness.assertContains(output, "C::<@A C>new");
        // 注解位于第二个类型实参:其余类型实参也须一并还原
        DecompileTestHarness.assertContains(output, "C::<String, @A Integer>m2");
    }
}
