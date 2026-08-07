package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class BdecEngineTest {

    @Test
    public void testEngineNameAndVersion() {
        List<DecompilerDiagnostic> diags = new ArrayList<>();
        BdecEngine engine = new BdecEngine(BdecConfig.defaults(), diags::add);
        assertEquals("bdec", engine.getName());
        assertEquals("0.1.0", engine.getVersion());
    }

    @Test
    public void testDecompileInvalidBytesReturnsError() {
        List<DecompilerDiagnostic> diags = new ArrayList<>();
        BdecEngine engine = new BdecEngine(BdecConfig.defaults(), diags::add);

        // Empty byte array is not a valid class file
        BdecResult result = engine.decompile("com/example/Test",
                new byte[0], DecompileContext.empty(BdecConfig.defaults()));

        assertFalse("decompile of invalid bytes should fail", result.success());
        assertNotNull("should have error cause", result.cause());
    }

    @Test
    public void testErrorHandling() {
        List<DecompilerDiagnostic> diags = new ArrayList<>();
        BdecEngine engine = new BdecEngine(BdecConfig.defaults(), diags::add);

        // Null bytes cause NPE caught by engine
        BdecResult result = engine.decompile("com/example/Broken",
                null, DecompileContext.empty(BdecConfig.defaults()));

        assertNotNull(result);
        assertFalse(result.success());
        assertNotNull(result.cause());
    }
}
