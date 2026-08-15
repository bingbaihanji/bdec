package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * RuntimeInvisible* 注解(CLASS retention)往返测试.
 *
 * <p>javac 对 {@code @Retention(CLASS)}(含无 {@code @Retention} 的默认形态)
 * 注解落 RuntimeInvisibleAnnotations / RuntimeInvisibleTypeAnnotations /
 * RuntimeInvisibleParameterAnnotations 属性,BDEC 此前完全丢弃.本测试锁定
 * 解析与合并输出.</p>
 *
 * <p>连带修复 javac 双发射去重:对同时声明"声明目标+TYPE_USE"的注解,同一成员上
 * 一个 {@code @A} 会同时落入声明注解属性与类型注解属性(元素根路径),此前输出
 * 重复注解无法重编译——保留声明版,剔除类型副本,数组自身注解({@code int @A []})
 * 不误删.</p>
 */
public class InvisibleAnnotationRoundTripTest {

    private static final String ANN_A = """
                                        import java.lang.annotation.*;
                                        @Retention(RetentionPolicy.CLASS)
                                        @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD,
                                                 ElementType.PARAMETER, ElementType.TYPE_USE})
                                        @interface A {
                                            String value() default "x";
                                        }
                                        """;

    @Test
    public void testClassRetentionAnnotationsOnMembers() throws Exception {
        String src = ANN_A + """
                             @A("cls")
                             class R {
                                 @A("f")
                                 int field;
                                 @A("m")
                                 String m(@A("p") String p) {
                                     @A("local") String s = p;
                                     return s;
                                 }
                             }
                             """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "R");
        // 类/字段/方法(含返回类型去重)/参数/局部变量全部还原,且不重复
        DecompileTestHarness.assertContains(out,
                "@A(\"cls\")\nclass R",
                "@A(\"f\")\n    int field",
                "@A(\"m\")\n    String m(@A(\"p\") String p)",
                "@A(\"local\") String s = p");
        DecompileTestHarness.assertNotContains(out, "@A(\"f\") @A(\"f\")");
        DecompileTestHarness.assertNotContains(out, "@A(\"m\") @A(\"m\")");
        DecompileTestHarness.assertNotContains(out, "@A(\"p\") @A(\"p\")");
        // 去重后必须可重编译(修复前重复注解非 repeatable 编译失败)
        DecompileTestHarness.assertRecompiles(out, "R", Map.of("A", ANN_A));
    }

    @Test
    public void testArraySelfAnnotationPreserved() throws Exception {
        // 显式数组自身注解(int @B [])与声明注解并存应保留两份,
        // 仅裸声明(int[] bare)的类型副本被剔除
        String ann = """
                     import java.lang.annotation.*;
                     @Retention(RetentionPolicy.CLASS)
                     @Target({ElementType.FIELD, ElementType.TYPE_USE})
                     @interface B {}
                     """;
        String src = ann + """
                           class S {
                               @B int @B [] arr;
                               @B int[] bare;
                           }
                           """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "S");
        DecompileTestHarness.assertContains(out, "int @B [] arr");
        DecompileTestHarness.assertNotContains(out, "int @B [] @B [] arr");
        DecompileTestHarness.assertRecompiles(out, "S", Map.of("B", ann));
    }

    @Test
    public void testRuntimeDualTargetDedupRegression() throws Exception {
        // 既有 bug 回归:RUNTIME retention + 声明/TYPE_USE 双目标也曾重复输出
        String ann = """
                     import java.lang.annotation.*;
                     @Retention(RetentionPolicy.RUNTIME)
                     @Target({ElementType.FIELD, ElementType.TYPE_USE})
                     @interface A {}
                     """;
        String src = ann + """
                           class R {
                               @A int field;
                           }
                           """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "R");
        DecompileTestHarness.assertContains(out, "@A\n    int field");
        DecompileTestHarness.assertNotContains(out, "@A\n    @A int field");
        DecompileTestHarness.assertRecompiles(out, "R", Map.of("A", ann));
    }
}
