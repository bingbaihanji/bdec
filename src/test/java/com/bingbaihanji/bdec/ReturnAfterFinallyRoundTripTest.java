package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * try-finally 之后 return 的定位正确性.
 *
 * <p>{@code try { ... } finally { ... } return X;} 的 return X 值在 finally
 * 之后求值,必须留在 try-finally 之后;而 {@code try { return X; } finally { ... }}
 * 的 return X 值在 finally 之前求值,必须留在 try 体内.此前 TryTranslator
 * 的退出块扩展把正常退出路径整体拉入 try 体,导致前者错误地输出
 * {@code try { ...; return x; } finally { x = 2; }}(return 提前求值,语义错误).</p>
 */
public class ReturnAfterFinallyRoundTripTest {

    @Test
    public void testReturnAfterFinallyStaysAfter() throws Exception {
        // 源码:try 之后 return this.x,return 值须在 finally 之后求值(x=2)
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    int x;\n"
                        + "    int m() { try { this.x = 1; } finally { this.x = 2; } return this.x; }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertContains(output, "this.x = 1;", "this.x = 2;", "return x;");
        // return x 必须位于 finally 之后(而非被拉入 try 体)
        assertTrue("return should follow finally", output.indexOf("finally") < output.indexOf("return x;"));
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }

    @Test
    public void testTailReturnStaysInTry() throws Exception {
        // 源码:try 内 return this.x,return 值须在 finally 之前求值(x=旧值)
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    int x;\n"
                        + "    int m() { try { return this.x; } finally { this.x = 2; } }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "return x;", "this.x = 2;");
        // return x 必须位于 finally 之前(保留在 try 体内)
        assertTrue("return should precede finally", output.indexOf("return x;") < output.indexOf("finally"));
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testReturnAfterFinallyLocal() throws Exception {
        // 局部变量 case:return y 在 finally 之后
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class D {\n"
                        + "    int x;\n"
                        + "    int m(int y) { try { this.x = 1; } finally { this.x = 2; } return y; }\n"
                        + "}\n",
                "D");
        DecompileTestHarness.assertContains(output, "return y;");
        assertTrue("return should follow finally", output.indexOf("finally") < output.indexOf("return y;"));
        DecompileTestHarness.assertRecompiles(output, "D", Map.of());
    }
}
