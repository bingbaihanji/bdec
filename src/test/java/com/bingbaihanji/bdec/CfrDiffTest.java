package com.bingbaihanji.bdec;

import org.junit.Assume;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 差分集成测试:BDEC 与 CFR 对同一样例的反编译输出都必须可重新编译.
 *
 * <p>本测试依赖外部 CFR jar(路径通过系统属性 {@code cfr.jar} 指定,
 * 或使用 maven 本地仓库默认路径).CFR jar 不存在时测试自动跳过——
 * 完整矩阵请使用 {@code tools/diff-test/diff_test.py}.</p>
 */
public class CfrDiffTest {

    private static final String SAMPLES_DIR = "src/test/resources/decompile-samples/m2-controlflow";

    /** 定位 CFR jar:系统属性 → maven 本地仓库(settings.xml 感知)→ null(跳过). */
    private static Path findCfrJar() {
        String prop = System.getProperty("cfr.jar");
        if (prop != null && Files.exists(Paths.get(prop))) {
            return Paths.get(prop);
        }
        Path cfr = mavenLocalRepository().resolve("org/benf/cfr/0.152/cfr-0.152.jar");
        return Files.exists(cfr) ? cfr : null;
    }

    /**
     * 解析 Maven 本地仓库路径:优先 {@code ~/.m2/settings.xml} 的
     * {@code <localRepository>} 覆盖,否则回退 {@code ~/.m2/repository}.
     */
    private static Path mavenLocalRepository() {
        Path settings = Paths.get(System.getProperty("user.home"), ".m2", "settings.xml");
        if (Files.exists(settings)) {
            try {
                String xml = Files.readString(settings, StandardCharsets.UTF_8);
                int start = xml.indexOf("<localRepository>");
                if (start >= 0) {
                    int valStart = start + "<localRepository>".length();
                    int valEnd = xml.indexOf("</localRepository>", valStart);
                    if (valEnd > valStart) {
                        return Paths.get(xml.substring(valStart, valEnd).trim());
                    }
                }
            } catch (Exception ignored) {
                // 解析失败回退默认路径
            }
        }
        return Paths.get(System.getProperty("user.home"), ".m2", "repository");
    }

    /** 运行命令,返回 (退出码, stdout+stderr). */
    private static int[] run(ProcessBuilder pb, StringBuilder out) throws Exception {
        // 合并 stderr 到 stdout,避免 CFR 大量写 stderr 时管道缓冲填满导致 waitFor() 挂死.
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] bytes = p.getInputStream().readAllBytes();
        out.append(new String(bytes, StandardCharsets.UTF_8));
        return new int[]{p.waitFor()};
    }

    /** 断言给定源码可独立重新编译(类路径指向样例 classes 目录). */
    private static void assertRecompiles(JavaCompiler compiler, Path classes, Path work,
                                         String who, String name, String source) {
        try {
            Path rcDir = work.resolve(who + "-rc");
            Files.createDirectory(rcDir);
            Path srcFile = rcDir.resolve(name + ".java");
            Files.writeString(srcFile, source, StandardCharsets.UTF_8);
            int rc = compiler.run(null, null, null,
                    "-d", rcDir.toString(), "-cp", classes.toString(),
                    srcFile.toString());
            org.junit.Assert.assertEquals(
                    who + " output for " + name + " must recompile:\n" + source, 0, rc);
        } catch (Exception e) {
            throw new RuntimeException(who + " recompile failed for " + name, e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    files.forEach(CfrDiffTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
        }
    }

    /**
     * 编译样例源码,分别用 BDEC 与 CFR 反编译,两份输出都必须可重新编译.
     * 样例集:控制流基础样例(数组/布尔方法/do-while/if-else/instanceof/new/静态调用).
     */
    @Test
    public void testBdecAndCfrOutputsRecompile() throws Exception {
        Path cfrJar = findCfrJar();
        Assume.assumeTrue("CFR jar not found - set -Dcfr.jar=/path/to/cfr.jar to enable",
                cfrJar != null);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assume.assumeTrue("requires JDK", compiler != null);

        Path samples = Paths.get(SAMPLES_DIR);
        String[] names = {"ArraySample", "BooleanMethodSample", "IfElseSample",
                "InstanceOfSample", "NewInstanceSample", "StaticCallSample", "SwitchSample",
                "TryFinallySample", "WhileLoopSample"};
        int checked = 0;
        for (String name : names) {
            Path src = samples.resolve(name + ".java");
            if (!Files.exists(src)) {
                continue;
            }
            Path work = Files.createTempDirectory("bdec-cfr-");
            try {
                // 1. javac 编译样例
                Path classes = work.resolve("classes");
                Files.createDirectory(classes);
                int rc = compiler.run(null, null, null,
                        "-g", "-d", classes.toString(), src.toString());
                org.junit.Assert.assertEquals("compile sample " + name, 0, rc);
                Path classFile = classes.resolve("test/" + name + ".class");
                if (!Files.exists(classFile)) {
                    classFile = classes.resolve(name + ".class");
                }
                org.junit.Assert.assertTrue("class file " + classFile, Files.exists(classFile));

                // 2. BDEC 反编译 + 重编译
                BdecConfig config = BdecConfig.builder().build();
                BdecEngine engine = new BdecEngine(config, d -> {});
                BdecResult result = engine.decompile(classFile,
                        new DecompileContext(config, n -> null));
                org.junit.Assert.assertTrue("bdec decompile " + name, result.success());
                assertRecompiles(compiler, classes, work, "bdec", name,
                        result.decompiledCode());

                // 3. CFR 反编译 + 重编译
                Path cfrOut = work.resolve("cfr");
                Files.createDirectory(cfrOut);
                StringBuilder cfrLog = new StringBuilder();
                ProcessBuilder pb = new ProcessBuilder(
                        "java", "-jar", cfrJar.toString(),
                        classFile.toString(), "--outputdir", cfrOut.toString());
                int[] rcArr = run(pb, cfrLog);
                org.junit.Assert.assertEquals("cfr decompile " + name + ": " + cfrLog,
                        0, rcArr[0]);
                Path cfrJava = Files.walk(cfrOut)
                        .filter(p -> p.toString().endsWith(".java"))
                        .findFirst().orElse(null);
                org.junit.Assert.assertNotNull("cfr output " + name, cfrJava);
                assertRecompiles(compiler, classes, work, "cfr", name,
                        Files.readString(cfrJava));
                checked++;
            } finally {
                deleteRecursively(work);
            }
        }
        org.junit.Assert.assertTrue("no samples checked", checked > 0);
    }
}
