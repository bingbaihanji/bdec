package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 方法调用三元({@code b ? foo() : bar()})的还原正确性.
 *
 * <p>与常量三元不同,方法调用分支的返回值经 {@code goto} 汇入合并点的 stack-PHI,
 * 分支体为单个表达式语句({@code this.foo()}/{@code this.bar()})而非空块.
 * 此前三元折叠守卫 {@code isEmptyBlock} 不命中,输出
 * {@code if (!b) { bar(); } else { foo(); } int y = foo();}——foo/bar 返回值被丢弃,
 * {@code foo()} 被无条件重复求值(语义错误).现已放宽为"单表达式语句或空"
 * 分支体即可折叠,还原为 {@code int y = b ? foo() : bar();}.</p>
 */
public class MethodCallTernaryRoundTripTest {

    @Test
    public void testMethodCallTernaryAssignment() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class A {\n"
                        + "    int foo() { return 1; }\n"
                        + "    int bar() { return 2; }\n"
                        + "    int m(boolean b) { int y = b ? foo() : bar(); return y; }\n"
                        + "}\n",
                "A");
        DecompileTestHarness.assertContains(output, "int y = b ? this.foo() : this.bar();");
        DecompileTestHarness.assertNotContains(output, "int y = this.foo();");
        DecompileTestHarness.assertRecompiles(output, "A", Map.of());
    }

    @Test
    public void testMethodCallTernaryReturn() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class B {\n"
                        + "    int foo() { return 1; }\n"
                        + "    int bar() { return 2; }\n"
                        + "    int m(boolean b) { return b ? foo() : bar(); }\n"
                        + "}\n",
                "B");
        DecompileTestHarness.assertContains(output, "return b ? this.foo() : this.bar();");
        DecompileTestHarness.assertRecompiles(output, "B", Map.of());
    }

    @Test
    public void testVoidBranchesNotFolded() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class C {\n"
                        + "    void foo() {}\n"
                        + "    void bar() {}\n"
                        + "    void m(boolean b) { if (b) foo(); else bar(); }\n"
                        + "}\n",
                "C");
        // void 调用无汇合 PHI,不应折叠为三元
        DecompileTestHarness.assertNotContains(output, "?:");
        DecompileTestHarness.assertRecompiles(output, "C", Map.of());
    }
}
