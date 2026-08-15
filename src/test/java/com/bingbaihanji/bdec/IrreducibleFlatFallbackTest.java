package com.bingbaihanji.bdec;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * 不可归约 CFG 的扁平 labeled-goto 兜底(参照 Procyon).
 *
 * <p>结构化为 if/loop 会丢失语义或产出错误结构时,以 {@code label:} + {@code goto}
 * 扁平输出保证<b>语义正确</b>.由于 Java 的 {@code goto} 是保留关键字,输出不可
 * 重编译(这是反编译器对不可归约代码的业界共识——Procyon 同样输出 {@code goto}),
 * 但操作与流程完整,比空方法体/静默错误结构诚实且可读.</p>
 */
public class IrreducibleFlatFallbackTest {

    @Test
    public void testFlatFallbackPreservesSemantics() throws Exception {
        byte[] bytes = IrreducibleClassGen.irrClassBytes();
        BdecConfig config = BdecConfig.builder().build();
        BdecEngine engine = new BdecEngine(config, d -> {});
        BdecResult result = engine.decompile("Irr", bytes, DecompileContext.empty(config));
        assertTrue("decompile failed: " + result.cause(), result.success());

        String out = result.decompiledCode();
        // 操作完整:循环体自增 + 返回
        assertTrue("应含循环体自增 var1++", out.contains("var1++"));
        assertTrue("应含 return var1", out.contains("return var1"));
        // 流程完整:标签 + goto 跳转
        assertTrue("应含标签 lbl", out.contains("lbl"));
        assertTrue("应含 goto 跳转", out.contains("goto"));
        // 非空方法体(此前不可归约输出为空方法)
        assertTrue("方法体不应为空", out.contains("if (param0 != 0)"));
    }
}
