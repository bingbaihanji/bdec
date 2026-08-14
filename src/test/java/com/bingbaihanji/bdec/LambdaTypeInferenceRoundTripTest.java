package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * P0:局部变量类型推断.
 *
 * <p>静态无参方法里,第一个局部变量落在 slot 0.此前 BlockReducer 的 STORE 声明
 * 用 {@code v.slot() != 0} 跳过了 slot 0,导致变量漏声明,最终被 SourceCleanup
 * 兜底成 {@code int f = 0}.这里断言赋值表达式(lambda/方法引用/new/invoke)的
 * 目标类型被正确还原,而非 {@code int} 兜底,且输出可重编译.</p>
 */
public class LambdaTypeInferenceRoundTripTest {

    @Test
    public void testLambdaTargetTypeInStaticMethod() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource(
                """
                import java.util.function.Function;
                class LambdaBody {
                  static int run() {
                    Function<Integer, Integer> f = x -> { int y = x * 2; return y + 1; };
                    return f.apply(3);
                  }
                }
                """,
                "LambdaBody");
        DecompileTestHarness.assertContains(out, "Function<Integer, Integer> f");
        DecompileTestHarness.assertNotContains(out, "int f");
        DecompileTestHarness.assertRecompiles(out, "LambdaBody", Map.of());
    }

    @Test
    public void testMethodRefTargetTypeInStaticMethod() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource(
                """
                import java.util.function.Function;
                class MethodRefs {
                  static Object refs() {
                    Function<String, Integer> f = String::length;
                    return f.apply("hello");
                  }
                }
                """,
                "MethodRefs");
        DecompileTestHarness.assertContains(out, "Function<String, Integer> f");
        DecompileTestHarness.assertNotContains(out, "int f");
        DecompileTestHarness.assertRecompiles(out, "MethodRefs", Map.of());
    }

    @Test
    public void testNewTargetTypeInStaticMethod() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileSource(
                """
                import java.util.ArrayList;
                import java.util.List;
                class GenericsPropagation {
                  static List<String> make() {
                    List<String> l = new ArrayList<>();
                    l.add("a");
                    return l;
                  }
                }
                """,
                "GenericsPropagation");
        DecompileTestHarness.assertContains(out, "List<String> l");
        DecompileTestHarness.assertNotContains(out, "int l");
        DecompileTestHarness.assertRecompiles(out, "GenericsPropagation", Map.of());
    }
}
