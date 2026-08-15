package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 反混淆往返测试(参照 CFR op02obf).
 *
 * <p>覆盖三类混淆的还原/语义保持:</p>
 * <ul>
 *   <li><b>NPE 触发混淆</b>({@code try { s.getClass(); } catch (NPE) {...}}):
 *       空检查调用是 catch 分支的控制流触发,不能消除——消除后 NPE 不再抛出,
 *       catch 永不执行(语义错误).RequireNonNullEliminator 对 try 区域内的
 *       空检查不消除;</li>
 *   <li><b>除零假 try</b>:恒抛异常(常量除零)的 try 体是死分支,catch 是主路径,
 *       结构保留且语义正确;</li>
 *   <li><b>数值混淆</b>(参照 CFR ControlFlowNumericObf):相邻 {@code x += a; x += b}
 *       合并为 {@code x += (a+b)}.</li>
 * </ul>
 */
public class DeobfuscationRoundTripTest {

    @Test
    public void testNpeTriggerClassPreserved() throws Exception {
        // getClass() 空检查在 try 内 → 不消除(NPE 是 catch 的控制流触发)
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                public class NPE {
                    static int m(String s) {
                        try {
                            s.getClass();
                            return -1;
                        } catch (NullPointerException e) {
                            return s.length();
                        }
                    }
                }
                """,
                "NPE");
        DecompileTestHarness.assertContains(out, "s.getClass()");
        DecompileTestHarness.assertContains(out,
                "catch (NullPointerException e)",
                "return s.length()");
        DecompileTestHarness.assertRecompiles(out, "NPE", Map.of());
    }

    @Test
    public void testRequireNonNullInTryPreserved() throws Exception {
        // Objects.requireNonNull 在 try 内 → 不消除
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.Objects;
                public class RNN {
                    static int m(String s) {
                        try {
                            Objects.requireNonNull(s);
                            return -1;
                        } catch (NullPointerException e) {
                            return s.length();
                        }
                    }
                }
                """,
                "RNN");
        DecompileTestHarness.assertContains(out, "requireNonNull");
        DecompileTestHarness.assertRecompiles(out, "RNN", Map.of());
    }

    @Test
    public void testRequireNonNullOutsideTryStillEliminated() throws Exception {
        // try 外的 requireNonNull 仍消除(常规空检查伪影)
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.Objects;
                public class RNO {
                    static String m(String s) {
                        Objects.requireNonNull(s);
                        return s.toUpperCase();
                    }
                }
                """,
                "RNO");
        // 常规空检查应消除(s.toUpperCase() 会 NPE 兜底)
        DecompileTestHarness.assertNotContains(out, "requireNonNull");
        DecompileTestHarness.assertRecompiles(out, "RNO", Map.of());
    }

    @Test
    public void testDivByZeroFakeTry() throws Exception {
        // 常量除零恒抛 → try 体是死分支,catch 是主路径,结构保留且语义正确
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                public class DIV {
                    static int m(int x) {
                        try {
                            int t = 1 / 0;
                            System.out.println("dead");
                        } catch (ArithmeticException e) {
                            return x + 1;
                        }
                        return -1;
                    }
                }
                """,
                "DIV");
        DecompileTestHarness.assertContains(out, "1 / 0");
        DecompileTestHarness.assertContains(out,
                "catch (ArithmeticException e)",
                "return x + 1");
        DecompileTestHarness.assertRecompiles(out, "DIV", Map.of());
    }

    @Test
    public void testAdjacentIncMerged() throws Exception {
        // 数值混淆:x += 100; x -= 98 → x += 2
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                public class INC {
                    static int m(int x) {
                        x += 100;
                        x -= 98;
                        return x;
                    }
                }
                """,
                "INC");
        DecompileTestHarness.assertContains(out, "x = x + 2");
        DecompileTestHarness.assertNotContains(out, "100");
        DecompileTestHarness.assertNotContains(out, "98");
        DecompileTestHarness.assertRecompiles(out, "INC", Map.of());
    }
}
