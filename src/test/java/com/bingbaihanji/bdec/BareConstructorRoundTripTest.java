package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 裸 new 表达式语句({@code new FileInputStream("x");})的还原正确性.
 *
 * <p>构造器有副作用,即便结果被 pop 也必须作为表达式语句输出.此前 NEW 的结果
 * 被合并的 {@code <init>} INVOKE(接收者 operand[0])消费,而该 INVOKE 被跳过,
 * 导致 {@code consumed} 判定 NEW 已消费,整条语句静默消失,输出空方法体.</p>
 */
public class BareConstructorRoundTripTest {

    @Test
    public void testBareConstructorInMethodBody() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.io.*;\n"
                        + "class C {\n"
                        + "    void f() throws IOException {\n"
                        + "        new FileInputStream(\"x\");\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "new FileInputStream(\"x\")");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testBareConstructorInTryBody() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.io.*;\n"
                        + "class C2 {\n"
                        + "    void g() throws IOException {\n"
                        + "        try { new FileInputStream(\"x\"); } catch (IOException e) { h(); }\n"
                        + "    }\n"
                        + "    void h() {}\n"
                        + "}\n",
                "C2");
        DecompileTestHarness.assertContains(output, "new FileInputStream(\"x\")");
        DecompileTestHarness.assertRecompiles(output, "C2", Map.of());
    }
}
