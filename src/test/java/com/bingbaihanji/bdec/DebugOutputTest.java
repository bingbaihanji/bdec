package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.bytecode.model.*;
import com.bingbaihanji.bdec.cfg.*;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public class DebugOutputTest {
    @Test
    public void printTryFinallyOutput() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String out = h.decompileResource("decompile-samples/m2-controlflow/TryFinallySample.java");
        System.out.println("=== TRY_FINALLY OUTPUT ===");
        System.out.println(out);
        System.out.println("=== END ===");
    }

    @Test
    public void printTryFinallyCFG() throws Exception {
        Path tmpDir = Files.createTempDirectory("bdec-debug-");
        Path srcFile = tmpDir.resolve("TryFinallySample.java");
        String source = """
            package test;
            import java.util.concurrent.locks.ReentrantLock;
            public class TryFinallySample {
                private final ReentrantLock lock = new ReentrantLock();
                public int test() {
                    lock.lock();
                    try {
                        return 42;
                    } finally {
                        lock.unlock();
                    }
                }
            }
            """;
        Files.writeString(srcFile, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        compiler.run(null, null, null, "-g", "-d", tmpDir.toString(), srcFile.toString());

        Path classFile = Files.walk(tmpDir).filter(p -> p.toString().endsWith(".class")).findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(classFile);

        ClassFileReader reader = new ClassFileReader();
        var cm = reader.read("TryFinallySample", bytes);

        for (var method : cm.methods()) {
            System.out.println("Method: " + method.name());
            if (method.instructions() == null) continue;
            System.out.println("  Instructions:");
            for (var insn : method.instructions()) {
                System.out.printf("    %d: %s%n", insn.offset(), insn.mnemonic());
            }
            System.out.println("  Exception handlers:");
            if (method.exceptionHandlers() != null) {
                for (var eh : method.exceptionHandlers()) {
                    System.out.printf("    startPc=%d endPc=%d handlerPc=%d catchType=%s%n",
                            eh.startPc(), eh.endPc(), eh.handlerPc(), eh.catchType());
                }
            }
        }

        // Cleanup
        for (Path f : Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(f);
        }
    }
}
