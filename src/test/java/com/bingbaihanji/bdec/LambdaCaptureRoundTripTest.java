package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * Lambda 捕获外部变量的声明保留.
 *
 * <p>JVM 将 lambda 捕获的局部变量编译为合成方法
 * {@code lambda$m$0(captured..., lambdaParams...)} 的前导参数,INDY 动态
 * 操作数即被捕获变量本身.反编译时必须保留捕获变量的局部声明
 * (如 {@code int x = 10;}),否则 lambda 体按名引用 {@code x} 却无声明,
 * 输出无法重编译.
 */
public class LambdaCaptureRoundTripTest {

    /** 捕获局部变量,无 lambda 形参:声明 {@code int x = 10;} 必须保留 */
    @Test
    public void testCapturedLocalKeepsDeclaration() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.function.Supplier;\n"
                        + "class Cap {\n"
                        + "    static Supplier<Integer> make() {\n"
                        + "        int x = 10;\n"
                        + "        return () -> x + 1;\n"
                        + "    }\n"
                        + "}\n",
                "Cap");
        DecompileTestHarness.assertContains(output, "int x = 10;");
        DecompileTestHarness.assertContains(output, "() -> x + 1");
        DecompileTestHarness.assertRecompiles(output, "Cap", java.util.Map.of());
    }

    /** 捕获局部变量 + lambda 形参:声明保留,形参类型保留 */
    @Test
    public void testCapturedPlusParamKeepsDeclaration() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.function.IntFunction;\n"
                        + "class Cap2 {\n"
                        + "    static IntFunction<Integer> make() {\n"
                        + "        int x = 10;\n"
                        + "        return y -> x + y;\n"
                        + "    }\n"
                        + "}\n",
                "Cap2");
        DecompileTestHarness.assertContains(output, "int x = 10;");
        DecompileTestHarness.assertContains(output, "(int y) -> x + y");
        DecompileTestHarness.assertRecompiles(output, "Cap2", java.util.Map.of());
    }

    /** 无捕获 lambda:仅形参,不凭空多出捕获声明 */
    @Test
    public void testPlainParamNoSpuriousCapture() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.function.IntUnaryOperator;\n"
                        + "class Cap3 {\n"
                        + "    static IntUnaryOperator make() {\n"
                        + "        return x -> x + 1;\n"
                        + "    }\n"
                        + "}\n",
                "Cap3");
        DecompileTestHarness.assertContains(output, "(int x) -> x + 1");
        DecompileTestHarness.assertRecompiles(output, "Cap3", java.util.Map.of());
    }
}
