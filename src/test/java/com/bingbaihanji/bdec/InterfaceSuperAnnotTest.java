package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 接口父类型注解(JSR-308 0x10 的 interface 索引)往返测试.
 * <p>
 * {@code class Foo implements @A Runnable} 由 javac 编码为
 * RuntimeVisibleTypeAnnotations 中的 CLASS_EXTENDS 条目
 * (supertype_index = 接口在 interfaces 数组中的下标),
 * 反编译输出应在 {@code implements} 子句中内联注解.
 */
public class InterfaceSuperAnnotTest {

    @Test
    public void testAnnotatedInterface() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource("""
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                @interface A {}
                class Foo implements @A Runnable {
                    public void run() {}
                }
                """, "Foo");
        DecompileTestHarness.assertContains(out, "implements @A Runnable");
    }

    @Test
    public void testAnnotatedInterfaceAmongSeveral() throws Exception {
        // 多接口场景:注解只出现在被注解的接口上,其余接口不受影响
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource("""
                import java.lang.annotation.*;
                import java.io.Serializable;
                @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                @interface A {}
                class Foo2 implements Runnable, @A Serializable {
                    public void run() {}
                }
                """, "Foo2");
        DecompileTestHarness.assertContains(out, "Runnable, @A Serializable");
    }

    @Test
    public void testAnnotatedInterfaceOnEnum() throws Exception {
        // 枚举实现被注解接口:interfaces 数组下标同样从 0 开始
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource("""
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                @interface A {}
                enum AnnotEnum implements @A Runnable {
                    X;
                    public void run() {}
                }
                """, "AnnotEnum");
        DecompileTestHarness.assertContains(out, "implements @A Runnable");
    }
}
