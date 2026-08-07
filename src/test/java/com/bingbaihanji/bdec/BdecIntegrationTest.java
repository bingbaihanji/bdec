package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class BdecIntegrationTest {

    @Test
    public void testDecompileTestClass() throws Exception {
        Path classPath = Path.of("TestClass.class");
        if (!Files.exists(classPath)) {
            // Try to compile it
            System.out.println("TestClass.class not found — compile it first with: javac TestClass.java");
            return;
        }

        List<DecompilerDiagnostic> diags = new ArrayList<>();
        BdecEngine engine = new BdecEngine(BdecConfig.defaults(), diags::add);
        byte[] bytes = Files.readAllBytes(classPath);

        BdecResult result = engine.decompile("TestClass", bytes,
                DecompileContext.empty(BdecConfig.defaults()));

        System.out.println("Diagnostics (" + diags.size() + "):");
        diags.forEach(d -> System.out.println("  " + d.level() + " [" + d.phase() + "] " + d.message()));

        if (!result.success()) {
            System.out.println("FAILED: " + result.cause());
            if (result.cause() != null) {
                result.cause().printStackTrace(System.out);
            }
        }

        assertTrue("decompile should succeed: " +
                        (result.cause() != null ? result.cause().getMessage() : "unknown"),
                result.success());

        System.out.println("=== DECOMPILED OUTPUT ===");
        System.out.println("'" + result.decompiledCode() + "'");
        System.out.println("=== END (" + result.decompiledCode().length() + " chars) ===");
    }
}
