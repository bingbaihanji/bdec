package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 多 catch 联合类型({@code catch (IOException | RuntimeException e)})的还原正确性.
 *
 * <p>javac 为同一 try 区域,同一处理器块发送<strong>多个</strong>异常表项,
 * 每项携带一个 catchType.此前 {@code TryTranslator} 按处理器合并时保留首个
 * catchType,丢掉其余类型,输出 {@code catch (IOException e)}——联合类型静默丢失.
 * 现已合并为 union 类型 {@code catch (IOException | RuntimeException e)}.</p>
 */
public class MultiCatchRoundTripTest {

    @Test
    public void testUnionTwoExceptions() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.io.*;\n"
                        + "class M {\n"
                        + "    void f() {\n"
                        + "        try { g(); }\n"
                        + "        catch (IOException | RuntimeException e) { h(); }\n"
                        + "    }\n"
                        + "    void g() throws IOException {}\n"
                        + "    void h() {}\n"
                        + "}\n",
                "M");
        DecompileTestHarness.assertContains(output, "catch (IOException | RuntimeException e)");
        DecompileTestHarness.assertRecompiles(output, "M", Map.of());
    }

    @Test
    public void testUnionThreeExceptions() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.io.*;\n"
                        + "import java.sql.*;\n"
                        + "class M3 {\n"
                        + "    void f() {\n"
                        + "        try { g(); }\n"
                        + "        catch (IOException | RuntimeException | SQLException e) { h(); }\n"
                        + "    }\n"
                        + "    void g() throws IOException, SQLException {}\n"
                        + "    void h() {}\n"
                        + "}\n",
                "M3");
        DecompileTestHarness.assertContains(output, "catch (IOException | RuntimeException | SQLException e)");
        DecompileTestHarness.assertRecompiles(output, "M3", Map.of());
    }
}
