package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 嵌套枚举静态调用目标输出简单名而非 {@code $} 二进名.
 *
 * <p>javac 把 {@code E.values()} 编译为 {@code INVOKESTATIC Outer$E.values()},
 * IrBuilder 在静态调用上标注 {@code DECLARING_CLASS = "Outer$E"}(内部名).
 * BlockReducer 此前仅剥离包前缀(最后一个 {@code /} 之后),对无斜杠的
 * {@code EnumBinName$Color} 直接保留 {@code $},输出 {@code EnumBinName$Color.values()}
 * ——{@code $} 不是合法 Java 标识符,javac 报"找不到符号",无法重编译.
 * 修复后输出同编译单元嵌套类的简单名 {@code Color.values()}.</p>
 */
public class NestedEnumStaticCallRoundTripTest {

    @Test
    public void testNestedEnumStaticCallSimpleName() throws Exception {
        // 复现用例:成员枚举的静态 values() 调用不得输出 $ 二进名
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "class EnumBinName {\n"
                        + "    enum Color { RED, GREEN }\n"
                        + "    public static String check() {\n"
                        + "        return Color.values().length + \"\";\n"
                        + "    }\n"
                        + "}\n",
                "EnumBinName");
        DecompileTestHarness.assertContains(out, "Color.values()");
        DecompileTestHarness.assertNotContains(out, "EnumBinName$Color");
        DecompileTestHarness.assertRecompiles(out, "EnumBinName", Map.of());
    }

    @Test
    public void testDeeperNestedEnumStaticCallSimpleName() throws Exception {
        // 更深嵌套:O$M$E 的静态调用在 M 内引用,简单名 E 在作用域内
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "class O {\n"
                        + "    static class M {\n"
                        + "        enum E { A }\n"
                        + "        String m() { return E.values().length + \"\"; }\n"
                        + "    }\n"
                        + "}\n",
                "O");
        DecompileTestHarness.assertContains(out, "E.values()");
        DecompileTestHarness.assertNotContains(out, "O$M$E");
        DecompileTestHarness.assertRecompiles(out, "O", Map.of());
    }
}
