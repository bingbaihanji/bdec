package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 变量声明类型收窄修复的往返测试.
 *
 * <p>复现 bug: {@code Object o = new ArrayList<String>();} 此前被反编译为
 * {@code ArrayList o = new ArrayList();}——变量声明类型被"收窄"为初始化式的
 * 具体类型,忽略了 LVT(局部变量表)声明的更宽类型.若 {@code o} 之后被赋
 * 非 ArrayList 值,反编译输出将产生语义错误.修复后应优先采用 LVT 类型,
 * 输出 {@code Object o = new ArrayList();}.</p>
 */
public class ObjectNarrowingRoundTripTest {

    /** 用 {@code -g:none} 编译(无 LVT),再反编译. */
    private static String decompileWithNoDebugInfo(String source, String className) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("bdec-test-nolvt-");
        try {
            java.nio.file.Path srcFile = tmpDir.resolve(className + ".java");
            java.nio.file.Files.writeString(srcFile, source, java.nio.charset.StandardCharsets.UTF_8);
            var err = new java.io.ByteArrayOutputStream();
            int rc = compiler.run(null, null, err,
                    "-g:none", "-d", tmpDir.toString(), srcFile.toString());
            if (rc != 0) {
                throw new RuntimeException("Compile failed:\n" + err);
            }
            java.nio.file.Path classFile = tmpDir.resolve(className + ".class");
            BdecConfig config = BdecConfig.builder().build();
            BdecEngine engine = new BdecEngine(config, d -> {});
            BdecResult result = engine.decompile(classFile, new DecompileContext(config, null));
            if (!result.success()) {
                throw new RuntimeException("Decompilation failed: " + result.cause());
            }
            return result.decompiledCode();
        } finally {
            try (var files = java.nio.file.Files.list(tmpDir)) {
                files.forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
            java.nio.file.Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    public void testObjectDeclNotNarrowed() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class NarrowCheck {\n"
                        + "    public static String check() {\n"
                        + "        Object o = new java.util.ArrayList<String>();\n"
                        + "        java.util.List x = java.util.Arrays.asList(o);\n"
                        + "        return x.toString();\n"
                        + "    }\n"
                        + "}\n",
                "NarrowCheck");
        DecompileTestHarness.assertContains(output, "Object o = new ArrayList");
        DecompileTestHarness.assertNotContains(output, "ArrayList o =");
        DecompileTestHarness.assertRecompiles(output, "NarrowCheck", Map.of());
    }

    @Test
    public void testGenericListDeclNotNarrowed() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "import java.util.ArrayList;\n"
                        + "class C {\n"
                        + "    void m() {\n"
                        + "        List<String> x = new ArrayList<>();\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "List<String> x = new ArrayList");
        DecompileTestHarness.assertNotContains(output, "ArrayList<String> x");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testInterfaceDeclUsedNotNarrowed() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "import java.util.ArrayList;\n"
                        + "class C {\n"
                        + "    void m() {\n"
                        + "        List<String> x = new ArrayList<String>();\n"
                        + "        x.add(\"a\");\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "List<String> x = new ArrayList");
        DecompileTestHarness.assertNotContains(output, "ArrayList<String> x");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testNullAssignmentUsesLvtType() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    void m() {\n"
                        + "        String s = null;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "String s = null;");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testReassignmentKeepsDeclaredType() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.ArrayList;\n"
                        + "import java.util.HashMap;\n"
                        + "class C {\n"
                        + "    void m() {\n"
                        + "        Object o = new ArrayList<String>();\n"
                        + "        o = new HashMap<String, Integer>();\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "Object o = new ArrayList");
        DecompileTestHarness.assertNotContains(output, "ArrayList o =");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testBooleanLocalLiteral() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    boolean m(boolean b) {\n"
                        + "        boolean r = true;\n"
                        + "        if (b) { r = false; }\n"
                        + "        return r;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "boolean r = true;");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testByteLocalConstant() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    byte m() {\n"
                        + "        byte b = 10;\n"
                        + "        return b;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "byte b = 10;");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testCharLocalConstant() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    char m() {\n"
                        + "        char c = ';';\n"
                        + "        return c;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "char c = ';';");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testLongAndDoubleLocals() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    long m() {\n"
                        + "        long a = 5L;\n"
                        + "        return a;\n"
                        + "    }\n"
                        + "    double n() {\n"
                        + "        double b = 1.5;\n"
                        + "        return b;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "long a = 5L;");
        DecompileTestHarness.assertContains(output, "double b = 1.5;");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testByteIncrement() throws Exception {
        // IINC 对 byte 局部变量:writeVar 类型取自 LVT(BYTE),b++ 应可重编译.
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    byte m(byte b) {\n"
                        + "        byte x = b;\n"
                        + "        x++;\n"
                        + "        return x;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "byte x = b;");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testShortLocalConstant() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    short m() {\n"
                        + "        short s = 300;\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "short s = 300;");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }

    @Test
    public void testNoLvtFallbackType() throws Exception {
        // 构造无 LVT 调试信息的 class:javac -g:none 后 LVT 缺失,
        // 此时变量类型必须回退到被存值 val.type(),不得为 null.
        String output = decompileWithNoDebugInfo(
                "class C {\n"
                        + "    Object m() {\n"
                        + "        Object o = new java.util.ArrayList<String>();\n"
                        + "        return o;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }
}
