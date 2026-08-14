package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * Round17:方法引用接收者上的类型注解的往返测试.
 *
 * <p>覆盖 JVMS 类型注解目标
 * 0x46 METHOD_REFERENCE({@code @A C::id})与
 * 0x45 CONSTRUCTOR_REFERENCE({@code @A C::new}),
 * 以及接收者注解与类型实参注解(0x4B)共存于同一站点的形态
 * ({@code @A C::<@A String>id}).
 */
public class MethodRefReceiverAnnotationRoundTripTest {

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

                void m() {
                    Function<String, String> f = @A C::id;
                    Supplier<C> s = @A C::new;
                    Function<String, String> g = @A C::<@A String>id;
                }
            }
            """;

    @Test
    public void testMethodRefReceiverAnnotation() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(SOURCE, "C");
        DecompileTestHarness.assertContains(output, "@A C::id");
        DecompileTestHarness.assertContains(output, "@A C::new");
        // 接收者注解与类型实参注解共存于同一站点
        DecompileTestHarness.assertContains(output, "@A C::<@A String>id");
    }
}
