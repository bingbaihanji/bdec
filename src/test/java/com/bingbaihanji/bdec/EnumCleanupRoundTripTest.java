package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 枚举输出清理测试(第 16 轮):
 * 项一 - 冗余 import 调查:枚举/注解类型的反编译输出不得出现
 *        {@code import java.lang.Enum;},{@code import java.lang.annotation.Annotation;}
 *        或 {@code extends Enum<...>} 泄漏.
 * 项二 - ACC_BRIDGE 桥接方法调查:枚举常量匿名体类中 javac 生成的桥接方法
 *        (如 I&lt;T&gt;.get() 的 Object get() 桥接)必须过滤,真实实现保留;
 *        常量匿名体必须被发出(此前因只检查枚举自身声明的抽象方法而整体丢失).
 */
public class EnumCleanupRoundTripTest {

    // ============ 项一:冗余 import 调查 ============

    @Test
    public void testEnumReferencingJavaLangEnumNoImport() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "enum E { A, B; Enum<?> f() { return A; } }", "E");
        DecompileTestHarness.assertContains(out, "Enum<?> f()");
        DecompileTestHarness.assertNotContains(out, "import java.lang.Enum;");
    }

    @Test
    public void testAnnotationWithElementNoRedundantImport() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "@interface Ann { String value(); }", "Ann");
        DecompileTestHarness.assertContains(out, "@interface Ann", "String value()");
        DecompileTestHarness.assertNotContains(out,
                "import java.lang.annotation.Annotation;");
    }

    @Test
    public void testSimpleEnumNoRedundantOutput() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader("enum E { A, B }", "E");
        DecompileTestHarness.assertContains(out,
                "enum E {",
                "A,",
                "B");
        DecompileTestHarness.assertNotContains(out,
                "import java.lang.Enum;",
                "java.lang.Enum",
                "extends Enum",
                "values()",
                "valueOf");
    }

    @Test
    public void testEnumBodyWithInterfaceNoRedundantImport() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "enum E implements Runnable { A { public void run() {} } }", "E");
        DecompileTestHarness.assertContains(out,
                "enum E implements Runnable",
                "A {",
                "public void run()");
        DecompileTestHarness.assertNotContains(out,
                "import java.lang.Enum;",
                "extends Enum");
    }

    @Test
    public void testAnnotationTypeNoRedundantOutput() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader("@interface Ann {}", "Ann");
        DecompileTestHarness.assertContains(out, "@interface Ann");
        DecompileTestHarness.assertNotContains(out,
                "import java.lang.annotation.Annotation;",
                "java.lang.annotation.Annotation",
                "extends Annotation");
    }

    // ============ 项二:ACC_BRIDGE 桥接方法调查 ============

    /**
     * javac 为 {@code A { public String get() {...} }} 生成 E$1.class,其中包含:
     * <ul>
     *   <li>{@code public String get();} — 真实实现(0x0001)</li>
     *   <li>{@code public Object get();} — ACC_BRIDGE|ACC_SYNTHETIC 桥接(0x1041)</li>
     * </ul>
     * 反编译输出必须:发出常量匿名体,保留 String get(),过滤 Object get() 桥接,
     * 且输出可重新编译.
     */
    @Test
    public void testEnumBridgeMethodFiltered() throws Exception {
        String src = "interface I<T> { T get(); }\n"
                + "enum E implements I<String> {\n"
                + "  A { public String get() { return \"a\"; } }\n"
                + "}\n";
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "E");
        DecompileTestHarness.assertContains(out,
                "enum E implements I<String>",
                "A {",
                "public String get()");
        DecompileTestHarness.assertNotContains(out,
                "Object get()",
                "import java.lang.Enum;",
                "extends Enum");
        // 输出必须能重新编译(旧版输出丢失常量体,编译报"E 不是抽象的")
        DecompileTestHarness.assertRecompiles(out, "E",
                Map.of("I", "interface I<T> { T get(); }"));
    }

    /**
     * 桥接过滤不得误伤真实实现:常量体只覆写 Object/Enum 的具体方法
     * (如 toString)时 javac 不生成桥接,方法必须原样保留.
     */
    @Test
    public void testEnumBodyOverrideToStringKept() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "enum E { A { public String toString() { return \"A\"; } } }", "E");
        DecompileTestHarness.assertContains(out,
                "A {",
                "public String toString()");
        DecompileTestHarness.assertRecompiles(out, "E", Map.of());
    }
}
