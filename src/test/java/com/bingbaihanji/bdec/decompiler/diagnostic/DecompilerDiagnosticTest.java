package com.bingbaihanji.bdec.decompiler.diagnostic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class DecompilerDiagnosticTest {

    @Test
    public void testInfoFactory() {
        var d = DecompilerDiagnostic.info("parser", "com/example/Foo", "parsed OK");
        assertEquals(DiagnosticLevel.INFO, d.level());
        assertEquals("parser", d.phase());
        assertEquals("com/example/Foo", d.className());
        assertNull(d.methodName());
        assertEquals(-1, d.bytecodeOffset());
        assertEquals("parsed OK", d.message());
        assertNull(d.cause());
    }

    @Test
    public void testWarningFactory() {
        var d = DecompilerDiagnostic.warning("cfg", "com/example/Foo", "bar(I)V", 42, "unreachable code");
        assertEquals(DiagnosticLevel.WARNING, d.level());
        assertEquals("bar(I)V", d.methodName());
        assertEquals(42, d.bytecodeOffset());
    }

    @Test
    public void testErrorFactory() {
        Exception e = new IllegalArgumentException("bad CP index");
        var d = DecompilerDiagnostic.error("parser", "com/example/Foo", "<init>()V", 0, "constant pool error", e);
        assertEquals(DiagnosticLevel.ERROR, d.level());
        assertSame(e, d.cause());
    }

    @Test
    public void testListenerFunctionalInterface() {
        DiagnosticListener listener = diagnostic -> {
            assertEquals(DiagnosticLevel.INFO, diagnostic.level());
        };
        listener.report(DecompilerDiagnostic.info("test", "Foo", "ok"));
    }
}
