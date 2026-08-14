package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * P5a:sealed 类型 permits 子句还原.
 * <p>
 * Java 22+ 编译的 sealed 类型不再设置 ACC_SEALED 标志位,仅保留
 * PermittedSubclasses 属性.SealedClassRewriter 需据此识别密封类型并输出
 * {@code permits} 子句,而非退化为普通 interface/class.
 * </p>
 */
public class SealedPermitsRoundTripTest {

    @Test
    public void testSealedInterfacePermits() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "sealed interface Shape permits Circle, Square {}\n"
                        + "final class Circle implements Shape {}\n"
                        + "final class Square implements Shape {}\n",
                "Shape");
        DecompileTestHarness.assertContains(output,
                "sealed interface Shape permits Circle, Square");
        DecompileTestHarness.assertNotContains(output, "interface Shape {");
    }

    @Test
    public void testSealedClassPermits() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "sealed class Node permits Leaf, Branch {}\n"
                        + "final class Leaf extends Node {}\n"
                        + "final class Branch extends Node {}\n",
                "Node");
        DecompileTestHarness.assertContains(output,
                "sealed class Node permits Leaf, Branch");
        DecompileTestHarness.assertNotContains(output, "class Node {");
    }

    @Test
    public void testSealedInterfacePermitsRecompiles() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "sealed interface Shape permits Circle, Square { int area(); }\n"
                        + "final class Circle implements Shape { public int area() { return 1; } }\n"
                        + "final class Square implements Shape { public int area() { return 2; } }\n",
                "Shape");
        DecompileTestHarness.assertContains(output,
                "sealed interface Shape permits Circle, Square");
        // 还原出的 permits 子句必须能被 javac 重新编译(子类作为伴随源码提供)
        DecompileTestHarness.assertRecompiles(output, "Shape", Map.of(
                "Circle", "final class Circle implements Shape { public int area() { return 1; } }",
                "Square", "final class Square implements Shape { public int area() { return 2; } }"));
    }
}
