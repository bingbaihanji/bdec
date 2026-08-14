package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticLevel;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * BDEC 命令行接口(CLI),提供反编译引擎的命令行交互功能.
 *
 * <p>用法示例:</p>
 * <pre>
 *   java -jar bdec.jar --help
 *   java -jar bdec.jar -class &lt;classfile&gt; [outputDir]
 *   java -jar bdec.jar -jar &lt;jarfile&gt; [outputDir]
 * </pre>
 */
public final class BdecCli {

    /** 当前版本号(引用 {@link BuildInfo} 单一事实源) */
    private static final String VERSION = BuildInfo.VERSION;

    /** 命令行帮助信息模板 */
    private static final String HELP = """
                                       BDEC (Bingbaihanji Decompiler) v%s — Java 反编译引擎

                                       用法:
                                         java -jar bdec.jar [选项]

                                       选项:
                                         --help, -h               显示此帮助信息
                                         --version, -v            显示版本号
                                         -class <文件> [输出目录]  反编译单个 .class 文件
                                                                  输出目录默认为当前目录
                                         -jar <文件> [输出目录]    反编译 JAR 包中全部 .class 文件
                                                                  输出目录默认为当前目录

                                       示例:
                                         java -jar bdec.jar -class "D:/hello/Hello.class" "D:/hello/"
                                         java -jar bdec.jar -jar "test.jar" ./
                                         java -jar bdec.jar -jar "lib.jar" ./output/

                                       输出:
                                         对每个类 com.example.Foo,生成 输出目录/com/example/Foo.java
                                       """.formatted(VERSION);

    /**
     * 命令行主入口方法,解析参数并分派到相应的反编译处理逻辑.
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println(HELP);
            return;
        }

        String cmd = args[0].toLowerCase();
        switch (cmd) {
            case "--help", "-h" -> System.out.println(HELP);
            case "--version", "-v" -> System.out.println("bdec v" + VERSION);
            case "-class" -> decompileClass(args);
            case "-jar" -> decompileJar(args);
            default -> {
                System.err.println("Unknown option: " + args[0]);
                System.err.println("Use --help for usage information.");
                System.exit(1);
            }
        }
    }

    /**
     * 反编译单个 .class 文件.
     *
     * <p>用法:{@code -class <classfile> [outputDir]}</p>
     *
     * @param args 命令行参数数组
     */
    private static void decompileClass(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: -class <classfile> [outputDir]");
            System.exit(1);
        }

        Path classFile = Path.of(args[1]);
        Path outputDir = args.length >= 3 ? Path.of(args[2]) : Path.of(".");

        if (!Files.exists(classFile)) {
            System.err.println("Error: class file not found: " + classFile);
            System.exit(1);
        }

        try {
            byte[] bytes = Files.readAllBytes(classFile);
            BdecConfig config = BdecConfig.defaults();
            List<DecompilerDiagnostic> diags = new ArrayList<>();
            BdecEngine engine = new BdecEngine(config, diags::add);

            // 直接从 class 文件字节中读取内部名称(最可靠的方式)
            String name = readInternalName(bytes, classFile.toString());

            // 创建类字节加载器以支持内部类反编译.
            // 解析 class 文件的包目录并从 classes 根目录加载内部类.
            // 例如外部类在 target/classes/com/example/Outer.class,
            // 内部类 target/classes/com/example/Outer$Inner.class
            // 需要从 classes 根目录通过内部名称加载.
            Path classFileParent = classFile.toAbsolutePath().getParent();
            // 通过从类文件路径中移除包目录来查找 classes 根目录
            Path classesRoot = classFileParent;
            int slashIdx = name.lastIndexOf('/');
            if (slashIdx >= 0) {
                String packagePath = name.substring(0, slashIdx);
                String parentPath = classFileParent.toString().replace('\\', '/');
                if (parentPath.endsWith(packagePath)) {
                    classesRoot = Path.of(parentPath.substring(0,
                            parentPath.length() - packagePath.length()));
                }
            }
            final Path root = classesRoot;
            java.util.function.Function<String, byte[]> loader = innerName -> {
                try {
                    Path innerFile = root.resolve(innerName + ".class");
                    if (Files.exists(innerFile)) {
                        return Files.readAllBytes(innerFile);
                    }
                } catch (Exception ignored) {
                }
                return null;
            };
            DecompileContext ctx = new DecompileContext(config, loader);
            BdecResult result = engine.decompile(name, bytes, ctx);

            if (!result.success()) {
                System.err.println("Decompile failed: " +
                        (result.cause() != null ? result.cause().getMessage() : "unknown"));
                System.exit(1);
            }

            // 将反编译结果写入输出文件
            writeJavaFile(outputDir, name, result.decompiledCode());

            // 输出诊断信息
            for (var d : diags) {
                if (d.level() == DiagnosticLevel.ERROR || d.level() == DiagnosticLevel.WARNING) {
                    System.err.println("  " + d.level() + " [" + d.phase() + "] " + d.message());
                }
            }

            System.out.println("Decompiled: " + name + " → " +
                    outputDir.resolve(name.replace('/', File.separatorChar) + ".java"));

        } catch (Exception e) {
            System.err.println("Error decompiling " + classFile + ": " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 反编译整个 JAR 包中的所有 .class 文件.
     *
     * <p>用法:{@code -jar <jarfile> [outputDir]}</p>
     *
     * @param args 命令行参数数组
     */
    private static void decompileJar(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: -jar <jarfile> [outputDir]");
            System.exit(1);
        }

        Path jarFile = Path.of(args[1]);
        Path outputDir = args.length >= 3 ? Path.of(args[2]) : Path.of(".");

        if (!Files.exists(jarFile)) {
            System.err.println("Error: JAR file not found: " + jarFile);
            System.exit(1);
        }

        BdecConfig config = BdecConfig.defaults();
        List<DecompilerDiagnostic> diags = new ArrayList<>();
        BdecEngine engine = new BdecEngine(config, diags::add);

        int success = 0, failed = 0;

        try (JarInputStream jis = new JarInputStream(
                new BufferedInputStream(Files.newInputStream(jarFile)))
        ) {

            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                // 跳过 module-info 和 package-info
                if (name.endsWith("module-info.class") || name.endsWith("package-info.class")) {
                    continue;
                }

                // 去掉 .class 后缀得到内部名称
                String internalName = name.substring(0, name.length() - 6);

                try {
                    byte[] bytes = jis.readAllBytes();
                    DecompileContext ctx = DecompileContext.empty(config);
                    BdecResult result = engine.decompile(internalName, bytes, ctx);

                    if (result.success()) {
                        writeJavaFile(outputDir, internalName, result.decompiledCode());
                        success++;
                        System.out.println("  OK  " + internalName);
                    } else {
                        failed++;
                        System.err.println("  FAIL " + internalName + ": " +
                                (result.cause() != null ? result.cause().getMessage() : "unknown"));
                    }
                } catch (Exception e) {
                    failed++;
                    System.err.println("  FAIL " + internalName + ": " + e.getMessage());
                }

                // 每处理 100 个类输出一次进度
                if ((success + failed) % 100 == 0) {
                    System.out.println("  ... " + success + " OK, " + failed + " failed");
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading JAR: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("\nDone: " + success + " classes decompiled, " + failed + " failed");
        System.out.println("Output: " + outputDir.toAbsolutePath().normalize());
    }

    /**
     * 将 Java 源代码写入输出目录中对应的文件路径.
     *
     * <p>根据内部类名自动创建目录结构并写入 .java 源文件.</p>
     *
     * @param outputDir    输出根目录
     * @param internalName 类的内部名称(如 {@code com/example/Foo})
     * @param source       Java 源代码字符串
     * @throws IOException 当文件写入失败时抛出
     */
    private static void writeJavaFile(Path outputDir, String internalName, String source) throws IOException {
        Path outPath = outputDir.resolve(internalName.replace('/', File.separatorChar) + ".java");
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, source);
    }

    /**
     * 从 class 文件字节码中提取类的内部名称.
     *
     * <p>通过 {@link com.bingbaihanji.bdec.bytecode.parser.ClassFileReader}
     * 解析字节码获取内部名称,比从文件路径推导更加可靠.</p>
     *
     * <p>Class 文件布局:magic(4字节) + minor(2字节) + major(2字节) + cp_count(2字节) + cp...<br>
     * 由于常量池长度可变,不能简单从固定偏移量读取 this_class 索引,
     * 因此使用 ClassFileReader 进行完整解析.</p>
     *
     * @param bytes        class 文件的字节数组
     * @param fallbackPath 解析失败时的回退文件路径
     * @return 类的内部名称
     */
    private static String readInternalName(byte[] bytes, String fallbackPath) {
        try {
            var reader = new com.bingbaihanji.bdec.bytecode.parser.ClassFileReader();
            var model = reader.read(fallbackPath, bytes);
            return model.internalName();
        } catch (Exception e) {
            // 解析失败时的回退方案:从文件路径提取简单类名
            String fileName = java.nio.file.Path.of(fallbackPath).getFileName().toString();
            return fileName.endsWith(".class")
                    ? fileName.substring(0, fileName.length() - 6) : fileName;
        }
    }
}
