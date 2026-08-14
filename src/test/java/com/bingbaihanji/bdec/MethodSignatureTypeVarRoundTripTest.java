package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 第 18 轮:parseMethodSignature 的类型变量切换为 kind=TYPE_VARIABLE
 * (收口 Round17 遗留的 CLASS 伪装兼容路径),MethodRefRewriter 与
 * LambdaRewriter 消费点改为显式接受新表示,输出保持字节级一致.
 */
public class MethodSignatureTypeVarRoundTripTest {

    @Test
    public void testGenericMethodRefNoExplicitTypeArgs() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.function.Function;\n"
                        + "class C {\n"
                        + "    static <T> T id(T t) { return t; }\n"
                        + "    void m() {\n"
                        + "        Function<String, String> f = C::id;\n"
                        + "        System.out.println(f);\n"
                        + "    }\n"
                        + "}\n",
                "C");
        // 方法引用的 SAM 接口类型现按短名渲染并收集 import(不再是全限定名)
        DecompileTestHarness.assertContains(output,
                "Function<String, String> f = C::id;",
                "import java.util.function.Function;");
        DecompileTestHarness.assertNotContains(output,
                "java.util.function.Function<String, String> f");
    }

    @Test
    public void testGenericLambdaTypeVariableParam() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "interface F<T> { T apply(T t); }\n"
                        + "class C {\n"
                        + "    <T> F<T> id() {\n"
                        + "        return t -> t;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        // lambda 参数类型为签名类型变量:发射仍跳过类型名(与旧 CLASS 伪装一致)
        DecompileTestHarness.assertContains(output, "return (t) -> t;");
        DecompileTestHarness.assertNotContains(output, "(T t) ->");
    }

    @Test
    public void testGenericMethodSignatureTypeVariableKind() throws Exception {
        // 直接断言 SignatureParser 入口行为(方法签名路径的类型变量 kind)
        com.bingbaihanji.bdec.type.JavaType[] sigTypes =
                com.bingbaihanji.bdec.bytecode.parser.SignatureParser
                        .parseMethodSignature("<T:Ljava/lang/Object;>(TT;)TT;");
        org.junit.Assert.assertNotNull(sigTypes);
        org.junit.Assert.assertEquals(2, sigTypes.length);
        com.bingbaihanji.bdec.type.JavaType param = sigTypes[0];
        com.bingbaihanji.bdec.type.JavaType ret = sigTypes[1];
        org.junit.Assert.assertEquals(
                com.bingbaihanji.bdec.type.TypeKind.TYPE_VARIABLE, param.kind());
        org.junit.Assert.assertEquals("T", param.displayName());
        org.junit.Assert.assertEquals("TT;", param.descriptor());
        org.junit.Assert.assertEquals(
                com.bingbaihanji.bdec.type.TypeKind.TYPE_VARIABLE, ret.kind());
        org.junit.Assert.assertEquals("T", ret.displayName());
        org.junit.Assert.assertEquals("TT;", ret.descriptor());
    }
}
