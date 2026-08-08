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
 * BDEC CLI — command-line interface for the decompiler engine.
 *
 * Usage:
 *   java -jar bdec.jar --help
 *   java -jar bdec.jar -class &lt;classfile&gt; [outputDir]
 *   java -jar bdec.jar -jar &lt;jarfile&gt; [outputDir]
 */
public final class BdecCli {

    private static final String VERSION = "0.1.0";

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
                                         对每个类 com.example.Foo，生成 输出目录/com/example/Foo.java
                                       """.formatted(VERSION);

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

    // === -class <file> [outDir] ===
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

            // Read internal name from the class file itself (most reliable)
            String name = readInternalName(bytes, classFile.toString());
            BdecResult result = engine.decompile(name, bytes, DecompileContext.empty(config));

            if (!result.success()) {
                System.err.println("Decompile failed: " +
                        (result.cause() != null ? result.cause().getMessage() : "unknown"));
                System.exit(1);
            }

            // Write output
            writeJavaFile(outputDir, name, result.decompiledCode());

            // Report diagnostics
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

    // === -jar <jarfile> [outDir] ===
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
                // Skip module-info and package-info
                if (name.endsWith("module-info.class") || name.endsWith("package-info.class")) {
                    continue;
                }

                String internalName = name.substring(0, name.length() - 6); // strip .class

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

                // Print progress every 100 classes
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

    // === Helpers ===

    private static void writeJavaFile(Path outputDir, String internalName, String source) throws IOException {
        Path outPath = outputDir.resolve(internalName.replace('/', File.separatorChar) + ".java");
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, source);
    }

    /**
     * Extract the internal class name directly from the class file bytes.
     * This is more reliable than trying to derive it from the file path.
     */
    private static String readInternalName(byte[] bytes, String fallbackPath) {
        try {
            // Class file layout: magic(4) + minor(2) + major(2) + cp_count(2) + cp...
            // We just need the this_class index at a fixed offset after the constant pool.
            // But the CP is variable-length. Use a full parse via ClassFileReader.
            var reader = new com.bingbaihanji.bdec.bytecode.parser.ClassFileReader();
            var model = reader.read(fallbackPath, bytes);
            return model.internalName();
        } catch (Exception e) {
            // Fallback: simple name from file path
            String fileName = java.nio.file.Path.of(fallbackPath).getFileName().toString();
            return fileName.endsWith(".class")
                    ? fileName.substring(0, fileName.length() - 6) : fileName;
        }
    }
}
