package com.bingbaihanji.bdec;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * 执行级语义等价测试 harness.
 *
 * <p>JADX 风格 check() round-trip:编译源码 → BDEC 反编译 → 重编译反编译产物 →
 * 用同一输入分别运行"原始 class"与"反编译→重编译 class",比对退出码与 stdout。
 * 捕获"能编译但行为错"的静默语义错误——文本断言({@link DecompileTestHarness} 的
 * assertContains/assertRecompiles)无法覆盖的层级。</p>
 *
 * <p><b>执行模式</b>:进程级 {@code java -cp}(默认)——天然防死循环(循环边界 bug
 * 正是目标)、防 {@code System.exit}/崩溃挂死测试 JVM、类路径隔离无同名类污染;
 * 代价是每次 JVM 启动约数百毫秒。样例约定:{@code public static String check()}
 * 返回确定性规范串,{@code main} 打印它,使 stdout 成为唯一比对通道。</p>
 *
 * <p><b>与 {@link DecompileTestHarness} 的关系</b>:后者在 finally 中删除临时编译目录,
 * 本 harness 需保留两份 class 目录到运行结束,故自管临时目录并直接复用
 * {@link BdecEngine} + {@link DecompileContext}。</p>
 */
public final class SemanticEquivalenceHarness {

    private SemanticEquivalenceHarness() {}

    /** 一次进程运行的结果. */
    public record RunResult(int exitCode, String stdout, String stderr, boolean timedOut) {}

    /** javac(source) → BDEC → javac(反编译产物) → 两侧运行 → 断言退出码+stdout 相等. */
    public static void assertSemanticallyEquivalent(String source, String className) throws Exception {
        assertSemanticallyEquivalent(source, className, Map.of());
    }

    /** companions:简单类名 → 源码,与反编译产物同目录一起 javac. */
    public static void assertSemanticallyEquivalent(String source, String className,
                                                    Map<String, String> companions) throws Exception {
        Path work = Files.createTempDirectory("bdec-semantic-");
        try {
            Path origClasses = work.resolve("orig-classes");
            Files.createDirectories(origClasses);
            Path topLevel = compileSource(source, className, origClasses);
            String decompiled = decompile(topLevel, origClasses);
            Path recompClasses = work.resolve("recomp-classes");
            Files.createDirectories(recompClasses);
            recompile(decompiled, className, companions, work.resolve("recomp-src"), recompClasses);
            String fqcn = deriveFqcn(source, className);
            RunResult orig = runMain(origClasses, fqcn);
            RunResult recomp = runMain(recompClasses, fqcn);
            assertEquivalent(className, orig, recomp);
        } finally {
            deleteRecursively(work);
        }
    }

    /** 资源便捷入口:{@code src/test/resources/behavior-samples/<simpleName>.java}. */
    public static void assertSemanticallyEquivalentResource(String simpleName) throws Exception {
        String source = readResource("behavior-samples/" + simpleName + ".java");
        assertSemanticallyEquivalent(source, simpleName);
    }

    /**
     * 用预先给定的反编译产物(跳过 BDEC 反编译一步)——供负面 smoke 注入
     * "能编译但行为错"的输出,证明执行级比对能抓住这类静默错误.
     */
    public static void assertRecompiledSemantics(String source, String className,
                                                 String decompiled, Map<String, String> companions) throws Exception {
        Path work = Files.createTempDirectory("bdec-semantic-");
        try {
            Path origClasses = work.resolve("orig-classes");
            Files.createDirectories(origClasses);
            compileSource(source, className, origClasses);
            Path recompClasses = work.resolve("recomp-classes");
            Files.createDirectories(recompClasses);
            recompile(decompiled, className, companions, work.resolve("recomp-src"), recompClasses);
            String fqcn = deriveFqcn(source, className);
            RunResult orig = runMain(origClasses, fqcn);
            RunResult recomp = runMain(recompClasses, fqcn);
            assertEquivalent(className, orig, recomp);
        } finally {
            deleteRecursively(work);
        }
    }

    /** 运行指定类目录下类的 main(String[]),捕获退出码与 stdout/stderr. */
    public static RunResult runMain(Path classesDir, String fqcn) throws Exception {
        String java = findJavaExecutable();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", classesDir.toString(), fqcn);
        Process p = pb.start();
        StreamDrain out = new StreamDrain(p.getInputStream());
        StreamDrain err = new StreamDrain(p.getErrorStream());
        out.start();
        err.start();
        boolean timedOut = !p.waitFor(30, TimeUnit.SECONDS);
        if (timedOut) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        return new RunResult(p.exitValue(), out.get(), err.get(), timedOut);
    }

    // ---------- 内部 ----------

    private static Path compileSource(String source, String className, Path outDir) throws IOException {
        Path srcFile = outDir.resolve(className + ".java");
        Files.writeString(srcFile, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available. Run with JDK, not JRE.");
        }
        int rc = compiler.run(null, null, null, "-g", "-d", outDir.toString(), srcFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("Compilation failed for " + className);
        }
        List<Path> classFiles = Files.walk(outDir)
                .filter(p -> p.toString().endsWith(".class"))
                .toList();
        for (Path cf : classFiles) {
            if (cf.getFileName().toString().equals(className + ".class")) {
                return cf;
            }
        }
        if (classFiles.isEmpty()) {
            throw new IllegalStateException("No .class produced for " + className);
        }
        return classFiles.get(0);
    }

    private static String decompile(Path classFile, Path classesDir) throws Exception {
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        final Path root = classesDir;
        Function<String, byte[]> loader = internalName -> {
            try {
                Path f = root.resolve(internalName + ".class");
                return Files.exists(f) ? Files.readAllBytes(f) : null;
            } catch (IOException e) {
                return null;
            }
        };
        DecompileContext ctx = new DecompileContext(config, loader);
        BdecResult result = engine.decompile(classFile, ctx);
        if (!result.success()) {
            throw new IllegalStateException("Decompilation failed: "
                    + (result.cause() != null ? result.cause().getMessage() : "unknown"));
        }
        return result.decompiledCode();
    }

    private static void recompile(String decompiled, String className,
                                  Map<String, String> companions, Path srcDir, Path outDir) throws IOException {
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve(className + ".java"), decompiled, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>();
        args.add("-g");
        args.add("-d");
        args.add(outDir.toString());
        args.add(srcDir.resolve(className + ".java").toString());
        for (Map.Entry<String, String> c : companions.entrySet()) {
            Files.writeString(srcDir.resolve(c.getKey() + ".java"), c.getValue(), StandardCharsets.UTF_8);
            args.add(srcDir.resolve(c.getKey() + ".java").toString());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available.");
        }
        int rc = compiler.run(null, null, err, args.toArray(new String[0]));
        if (rc != 0) {
            throw new IllegalStateException("Recompilation of decompiled " + className + " failed:\n"
                    + err.toString(StandardCharsets.UTF_8));
        }
    }

    private static String deriveFqcn(String source, String className) {
        Matcher m = PACKAGE_PATTERN.matcher(source);
        return m.find() ? m.group(1) + "." + className : className;
    }

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private static String findJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            for (String name : new String[] {"bin/java.exe", "bin/java"}) {
                Path candidate = Path.of(javaHome, name);
                if (Files.exists(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return "java";
    }

    private static void assertEquivalent(String name, RunResult orig, RunResult recomp) {
        assertFalse(name + ": 原始样例超时(样例本身死循环,无效)", orig.timedOut());
        assertFalse(name + ": 反编译产物超时(疑似反编译产生死循环)", recomp.timedOut());
        assertFalse(name + ": 原始样例无任何输出——样例退化,无效", orig.stdout().isEmpty());
        assertEquals(name + ": 退出码不一致\n[orig stderr]\n" + orig.stderr()
                        + "\n[recomp stderr]\n" + recomp.stderr(),
                orig.exitCode(), recomp.exitCode());
        assertEquals(name + ": stdout 不一致\n--- 原始 ---\n" + orig.stdout()
                        + "\n--- 重编译 ---\n" + recomp.stdout(),
                orig.stdout(), recomp.stdout());
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = SemanticEquivalenceHarness.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    files.forEach(SemanticEquivalenceHarness::deleteRecursively);
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    /** 后台排空子进程输出流的守护线程(防管道缓冲填满导致 waitFor 挂死). */
    private static final class StreamDrain extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamDrain(InputStream in) {
            super("bdec-drain");
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                in.transferTo(buffer);
            } catch (IOException ignored) {
            }
        }

        String get() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
