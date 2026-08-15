package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 嵌套类型引用的往返测试.
 *
 * <p>类内嵌套类型(接口/sealed/record/类)之间相互引用时,反编译须用嵌套
 * 简单名(如 {@code implements Shape})而非二进制名({@code implements
 * Outer$Shape}——非法标识符无法重编译).修复点:</p>
 * <ul>
 *   <li>{@code TypeReferenceUtil.renderClassRefAtPath}:父类/接口签名渲染根节点
 *       剥最后一个 {@code $} 段;</li>
 *   <li>{@code AstBuilder} 接口渲染无签名回退路径同样剥 {@code $}.</li>
 * </ul>
 */
public class NestedTypeReferenceRoundTripTest {

    @Test
    public void testNestedInterfaceImplement() throws Exception {
        // 嵌套接口 + 实现类:implements I 而非 Outer$I
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                class Outer {
                    interface I { String greet(); }
                    static class C implements I {
                        public String greet() { return "hi"; }
                    }
                    static String use() {
                        return new C().greet();
                    }
                }
                """,
                "Outer");
        DecompileTestHarness.assertContains(out, "implements I");
        DecompileTestHarness.assertNotContains(out, "Outer$I");
        DecompileTestHarness.assertNotContains(out, "$I");
        DecompileTestHarness.assertRecompiles(out, "Outer", Map.of());
    }

    @Test
    public void testNestedClassExtends() throws Exception {
        // 嵌套类继承:extends B 而非 Outer$B
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                class Outer {
                    static class B { int b() { return 1; } }
                    static class C extends B { int c() { return 2; } }
                }
                """,
                "Outer");
        DecompileTestHarness.assertContains(out, "extends B");
        DecompileTestHarness.assertNotContains(out, "Outer$B");
        DecompileTestHarness.assertRecompiles(out, "Outer", Map.of());
    }

    @Test
    public void testNestedSealedRecords() throws Exception {
        // 嵌套 sealed 接口 + record 实现(参照 RecordSealedSample)
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                class ShapeHost {
                    sealed interface Shape permits Circle, Square {
                        double area();
                    }
                    record Circle(double r) implements Shape {
                        public double area() { return r * r * 3.14; }
                    }
                    record Square(double s) implements Shape {
                        public double area() { return s * s; }
                    }
                    static double total(java.util.List<Shape> shapes) {
                        double sum = 0;
                        for (Shape s : shapes) sum += s.area();
                        return sum;
                    }
                }
                """,
                "ShapeHost");
        DecompileTestHarness.assertContains(out, "sealed interface Shape");
        DecompileTestHarness.assertContains(out, "implements Shape");
        DecompileTestHarness.assertNotContains(out, "ShapeHost$Shape");
        DecompileTestHarness.assertRecompiles(out, "ShapeHost", Map.of());
    }

    @Test
    public void testCrossNestedReference() throws Exception {
        // 方法参数/返回引用嵌套接口类型
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                class Outer {
                    interface Callback { void call(); }
                    static void invoke(Callback cb) { cb.call(); }
                    static Callback make() { return () -> {}; }
                }
                """,
                "Outer");
        DecompileTestHarness.assertContains(out, "Callback cb", "static Callback make()");
        DecompileTestHarness.assertNotContains(out, "Outer$Callback");
        DecompileTestHarness.assertRecompiles(out, "Outer", Map.of());
    }
}
