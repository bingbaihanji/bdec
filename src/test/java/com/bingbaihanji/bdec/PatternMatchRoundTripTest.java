package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * P3:模式匹配重建——instanceof 模式 / record 解构模式 / switch 模式.
 * <p>
 * 参考 CFR 的 {@code InstanceOfExpressionDefining} / {@code SwitchExpressionPatternMatching}
 * 与 Vineflower 的 {@code IfPatternMatchProcessor} 处理逻辑.
 * </p>
 */
public class PatternMatchRoundTripTest {

    @Test
    public void testInstanceofPattern() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class InstanceofPattern {\n"
                        + "    static String test(Object o) {\n"
                        + "        if (o instanceof String s) { return s; }\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "}\n",
                "InstanceofPattern");
        // 模式变量应被重建(而非保留强转)
        DecompileTestHarness.assertContains(output, "instanceof String");
        DecompileTestHarness.assertNotContains(output, "(String) o");
        DecompileTestHarness.assertRecompiles(output, "InstanceofPattern", java.util.Map.of());
    }

    @Test
    public void testRecordPattern() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class RecordPattern {\n"
                        + "    record Point(int x, int y) {}\n"
                        + "    static int sum(Object o) {\n"
                        + "        if (o instanceof Point(int x, int y)) { return x + y; }\n"
                        + "        return 0;\n"
                        + "    }\n"
                        + "}\n",
                "RecordPattern");
        // 记录解构模式应被重建为 instanceof Point(int x, int y)
        DecompileTestHarness.assertContains(output, "instanceof Point(int x, int y)");
        // 组件提取残留(槽位复用临时变量/守卫死代码)不得出现
        DecompileTestHarness.assertNotContains(output,
                "var1", "var4", "var5", "1 != 0", "Object var");
        DecompileTestHarness.assertRecompiles(output, "RecordPattern", java.util.Map.of());
    }

    @Test
    public void testCombinedInstanceofAndSwitch() throws Exception {
        // instanceof 模式变量与 switch 模式组合:槽位复用会把模式变量 "s" 与
        // switch 判别式复用同一槽位,SourceCleanup 若未识别简单 instanceof 模式
        // 变量,会误生成 "int string = 0;" 导致重编译失败.
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class Combined {\n"
                        + "    static String test(Object o) {\n"
                        + "        if (o instanceof String s) { return s; }\n"
                        + "        return switch (o) {\n"
                        + "            case Integer i when i > 0 -> \"pos\";\n"
                        + "            case Integer i -> \"neg\";\n"
                        + "            case null -> \"null\";\n"
                        + "            default -> \"other\";\n"
                        + "        };\n"
                        + "    }\n"
                        + "}\n",
                "Combined");
        // 模式变量应被重建,且不得出现为模式变量误生成的整型声明
        DecompileTestHarness.assertContains(output, "instanceof String");
        DecompileTestHarness.assertNotContains(output, "int string = 0", "(String) o");
        // typeSwitch 应还原为带模式 case 标签的 switch(而非合成 switchKey)
        DecompileTestHarness.assertContains(output,
                "case Integer i when i > 0", "case null");
        DecompileTestHarness.assertNotContains(output, "switchKey");
        DecompileTestHarness.assertRecompiles(output, "Combined", java.util.Map.of());
    }
}
