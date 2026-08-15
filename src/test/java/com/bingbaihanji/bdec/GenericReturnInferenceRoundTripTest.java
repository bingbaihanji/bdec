package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 泛型方法返回类型推断往返测试.
 *
 * <p>JDK 工厂方法/流式链的泛型返回(经 GenericMethodResolver 反射绑定
 * 方法签名类型变量)与声明(LVTT)/强转协同,输出正确且可重编译:
 * {@code Map.of("a",1) → Map<String,Integer>},{@code List.of(...) →
 * List<String>},{@code Collections.emptyList()} 靠目标类型,流式链 lambda
 * 参数类型恢复.</p>
 */
public class GenericReturnInferenceRoundTripTest {

    @Test
    public void testFactoryMethods() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class FM {
                    static String m1() {
                        Map<String, Integer> m = Map.of("a", 1);
                        return m.get("a").toString();
                    }
                    static String m2() {
                        List<String> l = List.of("a", "b");
                        return l.get(0).toUpperCase();
                    }
                    static String m3() {
                        List<String> e = Collections.emptyList();
                        return e.toString();
                    }
                    static String m4() {
                        return Collections.singletonList("v").get(0);
                    }
                }
                """,
                "FM");
        DecompileTestHarness.assertContains(out,
                "Map<String, Integer> m = Map.of(\"a\", 1)",
                "List<String> l = List.of(\"a\", \"b\")",
                "List<String> e = Collections.emptyList()");
        DecompileTestHarness.assertNotContains(out, "Map<Object");
        DecompileTestHarness.assertRecompiles(out, "FM", Map.of());
    }

    @Test
    public void testStreamChainLambdaParams() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                import java.util.stream.*;
                public class SC {
                    static String m1() {
                        return Stream.of("a", "b").map(s -> s.toUpperCase())
                                .collect(Collectors.joining(","));
                    }
                    static int m2() {
                        return Stream.of("a", "b").mapToInt(s -> s.length()).sum();
                    }
                    static List<Integer> m3() {
                        return Stream.of(1, 2, 3).map(n -> n * 2).toList();
                    }
                    static Optional<String> m4() {
                        return Stream.of("x").findFirst();
                    }
                }
                """,
                "SC");
        // lambda 参数类型恢复(String/Integer),无 Object 强转参
        DecompileTestHarness.assertContains(out,
                "map((s) -> s.toUpperCase())",
                "mapToInt((s) -> s.length())",
                "map((n) -> n * 2)",
                "Optional<String>");
        DecompileTestHarness.assertNotContains(out, "map((Object s)");
        DecompileTestHarness.assertRecompiles(out, "SC", Map.of());
    }

    @Test
    public void testNestedGenericBinding() throws Exception {
        // 内层 Map.of 推断 Map<String,Integer>,外层 List.of 绑定 E=该类型
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class NG {
                    static String m() {
                        List<Map<String, Integer>> l = List.of(Map.of("a", 1));
                        return l.get(0).get("a").toString();
                    }
                }
                """,
                "NG");
        DecompileTestHarness.assertContains(out,
                "List<Map<String, Integer>> l = List.of(Map.of(\"a\", 1))");
        DecompileTestHarness.assertRecompiles(out, "NG", Map.of());
    }
}
