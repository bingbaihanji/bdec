package com.bingbaihanji.bdec;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertTrue;

/**
 * JAR 批处理模式的反编译正确性.
 *
 * <p>此前 {@code -jar} 模式用 {@code DecompileContext.empty}(无跨类加载器),
 * 内部/匿名类被当作独立类反编译成带 {@code $} 的文件(非法类名),外层类反而
 * 缺失嵌套声明.修复:预扫描 JAR 建立"内部名 → 字节"映射,逐顶层类反编译时
 * 经 map-backed loader 内联嵌套类(参照 CFR/Procyon/Vineflower 的按名回查).
 * 本测试用同一机制(编译 → 收集 class 映射 → 反编译顶层类)验证嵌套枚举
 * 常量体与成员内部类被正确内联且可重编译.</p>
 */
public class JarModeRoundTripTest {

    private static void deleteRecursively(Path dir) {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    files.forEach(JarModeRoundTripTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
        }
    }

    @Test
    public void testJarModeInlinesNestedClasses() throws Exception {
        String src = "package p;\n"
                + "public class Outer {\n"
                + "    public enum Color {\n"
                + "        RED { public String desc() { return \"red\"; } },\n"
                + "        BLUE { public String desc() { return \"blue\"; } };\n"
                + "        public abstract String desc();\n"
                + "    }\n"
                + "    public class Inner { public int get() { return 42; } }\n"
                + "    public int run() { return new Inner().get() + Color.RED.desc().length(); }\n"
                + "}\n";

        // 编译源码 → 收集全部 class 字节(模拟 JAR 条目:内部名 → 字节)
        Path tmp = Files.createTempDirectory("bdec-jarmode-");
        try {
            Path srcFile = tmp.resolve("Outer.java");
            Files.writeString(srcFile, src, StandardCharsets.UTF_8);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            int rc = compiler.run(null, null, null, "-g", "-d", tmp.toString(), srcFile.toString());
            if (rc != 0) {
                throw new IllegalStateException("compile failed");
            }
            Map<String, byte[]> classBytes = new HashMap<>();
            try (var files = Files.walk(tmp)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String rel = tmp.relativize(p).toString().replace('\\', '/');
                    classBytes.put(rel.substring(0, rel.length() - 6), Files.readAllBytes(p));
                }
            }

            // 模拟 JAR 模式:跳过 $ 类,顶层类用 map-backed loader 反编译
            BdecConfig config = BdecConfig.builder().build();
            BdecEngine engine = new BdecEngine(config, d -> {});
            Function<String, byte[]> loader = classBytes::get;
            BdecResult result = engine.decompile("p/Outer", classBytes.get("p/Outer"),
                    new DecompileContext(config, loader));
            assertTrue("decompile failed: " + result.cause(), result.success());

            String out = result.decompiledCode();
            // 嵌套枚举常量体还原 + 成员内部类内联
            assertTrue(out.contains("RED {"));
            assertTrue(out.contains("return \"red\";"));
            assertTrue(out.contains("class Inner"));
            // 不输出带 $ 的独立类型声明(非法类名)
            assertTrue(!out.contains("class Outer$Color"));
            // 可重编译
            DecompileTestHarness.assertRecompiles(out, "Outer", Map.of());
        } finally {
            deleteRecursively(tmp);
        }
    }
}
