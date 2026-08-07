package com.bingbaihanji.bdec;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BdecConfigTest {

    @Test
    public void testDefaults() {
        BdecConfig c = BdecConfig.defaults();
        assertEquals(4, c.indentSize());
        assertTrue(c.decodeEnums());
        assertEquals(5, c.ssaThreshold());
        assertFalse(c.debugDumpCfg());
    }

    @Test
    public void testBuilderOverride() {
        BdecConfig c = BdecConfig.builder()
                .indentSize(2)
                .decodeEnums(false)
                .ssaThreshold(-1)
                .build();
        assertEquals(2, c.indentSize());
        assertFalse(c.decodeEnums());
        assertEquals(-1, c.ssaThreshold());
    }

    @Test
    public void testDebugConfig() {
        BdecConfig c = BdecConfig.debug();
        assertTrue(c.debugDumpCfg());
        assertTrue(c.debugDumpAst());
    }
}
