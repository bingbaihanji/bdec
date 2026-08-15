package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 冗余强转抑制往返测试.
 *
 * <p>泛型推断(实例方法返回 + 工厂方法)使操作数静态类型可确定时,javac 仍为
 * 源码显式强转发射 CHECKCAST(如 {@code (String) l.get(0)} 中 {@code l} 为
 * {@code List<String>}),BDEC 可安全移除冗余强转.{@code RedundantCastRewriter}
 * 在 for-each 重建之后运行,避免破坏 {@code ((T)it.next())} 的元素类型识别.</p>
 */
public class RedundantCastRoundTripTest {

    @Test
    public void testGenericGetCastSuppressed() throws Exception {
        // List<String>.get → String,强转冗余
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class RG {
                    static String m1(List<String> l) {
                        return (String) l.get(0);
                    }
                    static Integer m2(Map<String, Integer> m) {
                        return (Integer) m.get("a");
                    }
                    static int m3(Map<String, Integer> m) {
                        return (Integer) m.get("b");
                    }
                }
                """,
                "RG");
        DecompileTestHarness.assertContains(out,
                "return l.get(0)",
                "return m.get(\"a\")",
                "return m.get(\"b\")");
        DecompileTestHarness.assertNotContains(out, "(String) l.get");
        DecompileTestHarness.assertNotContains(out, "(Integer) m.get");
        DecompileTestHarness.assertRecompiles(out, "RG", Map.of());
    }

    @Test
    public void testFactoryChainCastSuppressed() throws Exception {
        // List.of("a","b") → List<String>,.get(0) → String,强转冗余
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class FC {
                    static String m() {
                        return (String) List.of("a", "b").get(0);
                    }
                }
                """,
                "FC");
        DecompileTestHarness.assertContains(out, "return List.of(\"a\", \"b\").get(0)");
        DecompileTestHarness.assertNotContains(out, "(String) List.of");
        DecompileTestHarness.assertRecompiles(out, "FC", Map.of());
    }

    @Test
    public void testRawCastPreserved() throws Exception {
        // raw List 的 (String) raw.get(0) 操作数为 Object,必须保留
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class RC {
                    static String m(List raw) {
                        return (String) raw.get(0);
                    }
                }
                """,
                "RC");
        DecompileTestHarness.assertContains(out, "return (String) raw.get(0)");
        DecompileTestHarness.assertRecompiles(out, "RC", Map.of());
    }

    @Test
    public void testForEachReconstructionUnaffected() throws Exception {
        // for-each 重建仍正常(强转抑制晚于 for-each 重建)
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "class FE {\n"
                        + "    static void m(List<String> items) {\n"
                        + "        for (String item : items) { System.out.println(item); }\n"
                        + "    }\n"
                        + "}\n",
                "FE");
        DecompileTestHarness.assertContains(out, "for (String");
        DecompileTestHarness.assertNotContains(out, ".iterator()");
        DecompileTestHarness.assertRecompiles(out, "FE", Map.of());
    }
}
