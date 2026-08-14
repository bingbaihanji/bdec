package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * P5b:record 紧凑构造器体还原.
 * <p>
 * javac 将 {@code record Range(int lo, int hi) { Range { ... } }} 反糖为带
 * 字段赋值的规范构造器,RecordRewriter 移除规范构造器时应保留其显式语句,
 * 还原为紧凑构造器 {@code Range { ... }},而非丢失成空 record 体.
 * </p>
 */
public class RecordCompactConstructorRoundTripTest {

    @Test
    public void testCompactConstructorValidationBody() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "record Range(int lo, int hi) {\n"
                        + "    Range {\n"
                        + "        if (lo > hi) {\n"
                        + "            throw new IllegalArgumentException();\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "Range");
        DecompileTestHarness.assertContains(output,
                "record Range(int lo, int hi)",
                "Range {",
                "throw new IllegalArgumentException");
        // 字段赋值(this.lo = lo)是规范构造器的反糖痕迹,不应出现在输出中
        DecompileTestHarness.assertNotContains(output, "this.lo = lo");
        // 还原出的紧凑构造器必须能重新编译
        DecompileTestHarness.assertRecompiles(output, "Range", java.util.Map.of());
    }

    @Test
    public void testPlainRecordHasNoCompactConstructor() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource("record Point(int x, int y) {}\n", "Point");
        DecompileTestHarness.assertContains(output, "record Point(int x, int y)");
        // 无紧凑构造器的 record 不应出现 "Point {"(避免伪造空紧凑构造器)
        DecompileTestHarness.assertNotContains(output, "Point {");
        DecompileTestHarness.assertRecompiles(output, "Point", java.util.Map.of());
    }

    @Test
    public void testMultipleStatementsCompactConstructor() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "record Pair(int a, int b) {\n"
                        + "    Pair {\n"
                        + "        if (a < 0) {\n"
                        + "            throw new IllegalArgumentException(\"a\");\n"
                        + "        }\n"
                        + "        if (b < 0) {\n"
                        + "            throw new IllegalArgumentException(\"b\");\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "Pair");
        DecompileTestHarness.assertContains(output,
                "record Pair(int a, int b)",
                "Pair {",
                "throw new IllegalArgumentException(\"a\")",
                "throw new IllegalArgumentException(\"b\")");
        DecompileTestHarness.assertNotContains(output, "this.a = a");
        DecompileTestHarness.assertRecompiles(output, "Pair", java.util.Map.of());
    }
}
