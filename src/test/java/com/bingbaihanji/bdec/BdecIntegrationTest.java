package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BdecIntegrationTest {

    /** 从测试 classpath 加载预编译样例类(由 Maven test-compile 阶段生成). */
    private static byte[] loadPrecompiledClass(String simpleName) throws Exception {
        var in = BdecIntegrationTest.class.getResourceAsStream(
                "/com/bytecode/test/" + simpleName + ".class");
        assertNotNull("precompiled test class not found: " + simpleName, in);
        return in.readAllBytes();
    }

    @Test
    public void testDecompileTestClass() throws Exception {
        String internalName = "com/bytecode/test/TestClass1";
        byte[] bytes = loadPrecompiledClass("TestClass1");

        List<DecompilerDiagnostic> diags = new ArrayList<>();
        BdecEngine engine = new BdecEngine(BdecConfig.defaults(), diags::add);

        BdecResult result = engine.decompile(internalName, bytes,
                DecompileContext.empty(BdecConfig.defaults()));

        assertTrue("decompile should succeed: " +
                        (result.cause() != null ? result.cause().getMessage() : "unknown"),
                result.success());
        assertNotNull("decompiled code should be non-null", result.decompiledCode());
        assertTrue("decompiled code should not be empty", !result.decompiledCode().isEmpty());
    }
}
