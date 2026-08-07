package com.bingbaihanji.bdec;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DecompileContextTest {

    @Test
    public void testEmptyContext() {
        DecompileContext ctx = DecompileContext.empty(BdecConfig.defaults());
        assertNotNull(ctx.config());
        assertNull(ctx.loadClassBytes("java/lang/Object"));
    }

    @Test
    public void testWithLoader() {
        byte[] dummy = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        DecompileContext ctx = new DecompileContext(BdecConfig.defaults(), name -> {
            if ("java/lang/Object".equals(name)) {
                return dummy;
            }
            return null;
        });
        assertArrayEquals(dummy, ctx.loadClassBytes("java/lang/Object"));
        assertNull(ctx.loadClassBytes("com/example/Unknown"));
    }
}
