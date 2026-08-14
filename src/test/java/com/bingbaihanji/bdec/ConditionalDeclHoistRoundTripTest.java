package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 条件赋值菱形合并的声明提升正确性.
 *
 * <p>{@code int y; if (b) { side(); y = foo(); } else { y = bar(); } return y;}
 * 中,y 在两个分支各自声明(BlockReducer 按分支独立作用域翻译),作用域局限于分支内,
 * 合并点后的 {@code return y} 引用未声明,SourceCleanup 曾误补 {@code int y = 0}
 * (语义错误:无论 b 真假都返回 0).现已将声明提升为块级 {@code int y;},
 * 分支内声明转为赋值 {@code y = foo();}/{@code y = bar();}.</p>
 */
public class ConditionalDeclHoistRoundTripTest {

    @Test
    public void testConditionalDeclHoisted() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int foo() { return 1; }\n"
                        + "    int bar() { return 2; }\n"
                        + "    void side() {}\n"
                        + "    int m(boolean b) { int y; if (b) { side(); y = foo(); } else { y = bar(); } return y; }\n"
                        + "}\n",
                "A");
        // 提升后的块级声明(无初始化器)
        DecompileTestHarness.assertContains(output, "int y;");
        // 两分支内声明已转为赋值
        DecompileTestHarness.assertContains(output, "y = this.foo();");
        DecompileTestHarness.assertContains(output, "y = this.bar();");
        // 不得再出现误补的默认值声明
        DecompileTestHarness.assertNotContains(output, "int y = 0;");
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testConditionalDeclHoistedNoSideEffect() throws Exception {
        // 无副作用分支(纯赋值),同样应提升声明而非重复声明两处
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    int foo() { return 1; }\n"
                        + "    int bar() { return 2; }\n"
                        + "    int m(boolean b) { int y; if (b) { y = foo(); } else { y = bar(); } return y; }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertContains(output, "int y;");
        DecompileTestHarness.assertNotContains(output, "int y = 0;");
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }
}
