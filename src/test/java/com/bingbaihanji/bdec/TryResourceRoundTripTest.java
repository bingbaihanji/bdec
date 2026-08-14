package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * P4:try-with-resources 重建——javac 去糖化产生的
 * {@code r = new Resource(...); try { ... } finally { r.close(); }}
 * 应还原为 {@code try (Resource r = new Resource(...)) { ... }}.
 */
public class TryResourceRoundTripTest {

    @Test
    public void testSingleResource() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.io.BufferedReader;\n"
                        + "import java.io.FileReader;\n"
                        + "class TryRes {\n"
                        + "    static String read() throws Exception {\n"
                        + "        try (var r = new BufferedReader(new FileReader(\"x\"))) {\n"
                        + "            return r.readLine();\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "TryRes");
        // 资源声明应并入 try(...),close() 不再出现在 finally 中
        DecompileTestHarness.assertContains(output, "try (BufferedReader r = new BufferedReader");
        DecompileTestHarness.assertNotContains(output, "finally");
        DecompileTestHarness.assertRecompiles(output, "TryRes", java.util.Map.of());
    }

    @Test
    public void testResourceWithoutCatch() throws Exception {
        // close() 不抛受检异常的资源(如 Scanner),去糖化不含 catch 子句,
        // 应还原为纯 try (resource) 且不残留 finally
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.Scanner;\n"
                        + "class TryRes2 {\n"
                        + "    static String go() {\n"
                        + "        try (var s = new Scanner(\"hello\")) {\n"
                        + "            return s.next();\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "TryRes2");
        DecompileTestHarness.assertContains(output, "try (Scanner s = new Scanner");
        DecompileTestHarness.assertNotContains(output, "finally");
        DecompileTestHarness.assertRecompiles(output, "TryRes2", java.util.Map.of());
    }

    @Test
    public void testMultipleResources() throws Exception {
        // 多资源 try (br; pw):两个资源都应并入 try(...),且各自的 close()
        // 不再显式出现在 try 体中(交由 try-with-resources 在异常路径自动关闭).
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.io.BufferedReader;\n"
                        + "import java.io.PrintWriter;\n"
                        + "import java.io.StringReader;\n"
                        + "import java.io.StringWriter;\n"
                        + "class TryRes3 {\n"
                        + "    static String go(String s) throws Exception {\n"
                        + "        try (BufferedReader br = new BufferedReader(new StringReader(s));\n"
                        + "             PrintWriter pw = new PrintWriter(new StringWriter())) {\n"
                        + "            String line = br.readLine();\n"
                        + "            pw.println(line);\n"
                        + "            return line;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "TryRes3");
        DecompileTestHarness.assertContains(output, "try (BufferedReader br = new BufferedReader");
        DecompileTestHarness.assertContains(output, "PrintWriter pw = new PrintWriter");
        DecompileTestHarness.assertNotContains(output, "pw.close()");
        DecompileTestHarness.assertNotContains(output, "br.close()");
        DecompileTestHarness.assertNotContains(output, "finally");
        DecompileTestHarness.assertRecompiles(output, "TryRes3", java.util.Map.of());
    }

    @Test
    public void testReturnValueTempFolding() throws Exception {
        // javac 对 try-with-resources 中的 return 引入合成临时变量保存返回值,
        // 应折叠回 return line;(不残留 String varN = line 的合成临时变量)
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.io.BufferedReader;\n"
                        + "import java.io.FileReader;\n"
                        + "class TryResTemp {\n"
                        + "    static String read() throws Exception {\n"
                        + "        try (var r = new BufferedReader(new FileReader(\"x\"))) {\n"
                        + "            String line = r.readLine();\n"
                        + "            return line;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "TryResTemp");
        DecompileTestHarness.assertContains(output, "return line;");
        DecompileTestHarness.assertNotContains(output, "String var");
        DecompileTestHarness.assertRecompiles(output, "TryResTemp", java.util.Map.of());
    }
}
