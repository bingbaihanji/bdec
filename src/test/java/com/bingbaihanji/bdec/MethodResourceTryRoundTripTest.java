package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 方法调用资源 try-with-resources 重建往返测试.
 *
 * <p>javac 对工厂返回的资源({@code try (Type r = factory())})发射
 * {@code if (r != null) r.close()} 空值守卫(new 资源无此守卫)——守卫改变
 * handler 结构,TryTranslator 的 finally 副本识别因操作码序列不匹配未命中,
 * close 落 try 体 + 空 catch.修复:TryResourceRewriter 识别"try 体尾部
 * null 守卫 close + 单一空 catch(Throwable)"形态并还原 try-with-resources.</p>
 */
public class MethodResourceTryRoundTripTest {

    @Test
    public void testMethodCallResourceStatementBody() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.io.BufferedReader;
                import java.nio.file.Files;
                import java.nio.file.Paths;
                class MRT {
                    static void read(String path) throws Exception {
                        try (BufferedReader br = Files.newBufferedReader(Paths.get(path))) {
                            System.out.println(br.readLine());
                        }
                    }
                }
                """,
                "MRT");
        DecompileTestHarness.assertContains(out, "try (BufferedReader br = Files.newBufferedReader");
        DecompileTestHarness.assertNotContains(out, "br.close()");
        DecompileTestHarness.assertNotContains(out, "catch (Throwable");
        DecompileTestHarness.assertRecompiles(out, "MRT", Map.of());
    }

    @Test
    public void testHelperMethodResource() throws Exception {
        // 简单工厂方法资源
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                class HRT {
                    static java.util.Scanner make() { return new java.util.Scanner("x"); }
                    static void go() {
                        try (var s = make()) {
                            System.out.println(s.next());
                        }
                    }
                }
                """,
                "HRT");
        DecompileTestHarness.assertContains(out, "try (Scanner s = HRT.make())");
        DecompileTestHarness.assertNotContains(out, "s.close()");
        DecompileTestHarness.assertRecompiles(out, "HRT", Map.of());
    }

    @Test
    public void testNewResourceStillWorks() throws Exception {
        // new 资源不回归
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.io.BufferedReader;
                import java.io.FileReader;
                class NRT {
                    static void read(String path) throws Exception {
                        try (var r = new BufferedReader(new FileReader(path))) {
                            System.out.println(r.readLine());
                        }
                    }
                }
                """,
                "NRT");
        DecompileTestHarness.assertContains(out, "try (BufferedReader r = new BufferedReader");
        DecompileTestHarness.assertRecompiles(out, "NRT", Map.of());
    }
}
