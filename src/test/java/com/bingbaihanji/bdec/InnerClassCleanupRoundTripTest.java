package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * P2:合成类消除——成员内部类 this$0 / 匿名类内联 / 捕获局部变量 val$.
 * <p>
 * 参考 CFR 的 {@code CodeAnalyserWholeClass} 与 Vineflower 的
 * {@code NestedClassProcessor} 处理逻辑:隐藏合成外围引用字段,还原直接字段访问,
 * 内联匿名类并把 {@code val$X} 还原为原局部变量名.
 * </p>
 */
public class InnerClassCleanupRoundTripTest {

    @Test
    public void testMemberInnerClassThisCleanup() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class InnerAccess {\n"
                        + "    private int secret = 1;\n"
                        + "    class Inner { int get() { return secret; } }\n"
                        + "    int use() { return new Inner().get(); }\n"
                        + "}\n",
                "InnerAccess");
        DecompileTestHarness.assertContains(output,
                "class InnerAccess",
                "class Inner",
                "return secret",
                "new Inner()");
        // 合成外围引用痕迹不得出现
        DecompileTestHarness.assertNotContains(output,
                "this$0", "access$", "new Inner(this)");
        DecompileTestHarness.assertRecompiles(output, "InnerAccess", java.util.Map.of());
    }

    @Test
    public void testAnonymousClassInlinedWithCapturedLocal() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class AnonymousClass {\n"
                        + "    int field = 5;\n"
                        + "    Runnable make() {\n"
                        + "        int local = 10;\n"
                        + "        return new Runnable() {\n"
                        + "            public void run() { System.out.println(field + local); }\n"
                        + "        };\n"
                        + "    }\n"
                        + "}\n",
                "AnonymousClass");
        DecompileTestHarness.assertContains(output,
                "class AnonymousClass",
                "new Runnable() {",
                "public void run()",
                "field + local");
        // 匿名类独立类型与合成字段/捕获痕迹不得出现
        DecompileTestHarness.assertNotContains(output,
                "AnonymousClass$1", "this$0", "val$local", "param1");
        DecompileTestHarness.assertRecompiles(output, "AnonymousClass", java.util.Map.of());
    }

    @Test
    public void testAnonymousClassVariableDeclaration() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "class VarAnon {\n"
                        + "    Runnable make() {\n"
                        + "        int local = 1;\n"
                        + "        Runnable r = new Runnable() {\n"
                        + "            public void run() { System.out.println(local); }\n"
                        + "        };\n"
                        + "        return r;\n"
                        + "    }\n"
                        + "}\n",
                "VarAnon");
        DecompileTestHarness.assertContains(output, "new Runnable() {", "local");
        DecompileTestHarness.assertNotContains(output, "VarAnon$1", "this$0", "val$local");
        DecompileTestHarness.assertRecompiles(output, "VarAnon", java.util.Map.of());
    }
}
