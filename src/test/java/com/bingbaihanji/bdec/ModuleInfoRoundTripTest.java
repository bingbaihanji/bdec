package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * module-info.class(ACC_MODULE)反编译往返测试.
 *
 * <p>从 {@link BytecodeTestRoundTripTest} 拆分:模块声明(module/requires
 * 子句)必须从 Module 属性还原,且输出可被 javac 重新编译.</p>
 */
public class ModuleInfoRoundTripTest {

    @Test
    public void testModuleInfoDecompile() throws Exception {
        // module-info.class(ACC_MODULE)→ Module 属性还原 module 声明
        DecompileTestHarness harness = new DecompileTestHarness();
        String out = harness.decompileSource(
                "module testmodule { requires transitive java.sql; }",
                "module-info");
        DecompileTestHarness.assertContains(out,
                "module testmodule {",
                "requires transitive java.sql;");
        // 反编译输出必须可重新编译
        DecompileTestHarness.assertRecompiles(out, "module-info", Map.of());
    }
}
