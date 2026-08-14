package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 枚举注解位往返测试.
 * <p>
 * 实证(javac/javap)结论:
 * <ul>
 *   <li>枚举常量上的 RUNTIME 注解(如 {@code @Deprecated},Java 9 起
 *       RUNTIME retention)落在常量字段的 RuntimeVisibleAnnotations;</li>
 *   <li>枚举构造器参数注解编码为 RuntimeVisibleParameterAnnotations,
 *       javac 只发射真实参数条目(JVM 反射按尾部对齐到描述符参数);</li>
 *   <li>枚举类上注解落在类的 RuntimeVisibleAnnotations,
 *       接口注解落在 RuntimeVisibleTypeAnnotations 的 CLASS_EXTENDS.</li>
 * </ul>
 */
public class EnumConstantAnnotTest {

    @Test
    public void testEnumConstantDeprecatedAnnotation() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource("""
                enum E {
                    @Deprecated A,
                    B
                }
                """, "E");
        DecompileTestHarness.assertContains(out, "@Deprecated A");
    }

    @Test
    public void testEnumConstantCustomAnnotation() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource("""
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.FIELD, ElementType.TYPE_USE})
                @interface Tag { String value(); }
                enum E2 {
                    @Tag("x") A,
                    B
                }
                """, "E2");
        DecompileTestHarness.assertContains(out, "@Tag(\"x\") A");
    }

    @Test
    public void testEnumConstructorParameterAnnotation() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource("""
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
                @interface P {}
                enum E3 {
                    X("arg");
                    E3(@P String s) {}
                }
                """, "E3");
        DecompileTestHarness.assertContains(out, "@P String s");
    }
}
