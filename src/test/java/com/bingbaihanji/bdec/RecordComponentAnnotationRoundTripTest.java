package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * record 组件注解往返测试.
 *
 * <p>javac 把 record 组件声明上的注解写入 {@code Record} 属性组件属性:
 * 目标含 FIELD 的注解会复制到 backing 字段声明,目标不含 FIELD 的
 * (如 {@code @Target(RECORD_COMPONENT)} 或仅 TYPE_USE)只留在 Record 属性.
 * BDEC 此前完全不解析组件属性,组件注解整体丢失.修复:解析组件
 * Signature/Annotations/TypeAnnotations,字段渲染以组件注解为权威源,
 * RecordRewriter 内联到组件类型之前.</p>
 */
public class RecordComponentAnnotationRoundTripTest {

    private static final String ANNOT_IMPORTS = "import java.lang.annotation.*;\n";
    private static final String ANN_A = """
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD,
                     ElementType.PARAMETER, ElementType.TYPE_USE})
            @interface A { String value() default ""; }
            """;
    private static final String ANN_B = """
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
            @interface B { String value() default ""; }
            """;
    private static final String ANN_T = """
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE_USE)
            @interface T {}
            """;

    @Test
    public void testComponentAnnotationsVisibleAndInvisible() throws Exception {
        String src = ANNOT_IMPORTS + ANN_A + ANN_B + """
                record Rec(@A("c") int x, @A("d") @B("t") String y) {}
                """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "Rec");
        DecompileTestHarness.assertContains(out,
                "record Rec(@A(\"c\") int x, @A(\"d\") @B(\"t\") String y)");
        // 不重复(声明/TYPE_USE 双发射已被 AstBuilder stripDeclarationDupes 剔除)
        DecompileTestHarness.assertNotContains(out, "@A(\"c\") @A(\"c\")");
        DecompileTestHarness.assertRecompiles(out, "Rec",
                Map.of("A", ANNOT_IMPORTS + ANN_A, "B", ANNOT_IMPORTS + ANN_B));
    }

    @Test
    public void testGenericComponentWithAnnotation() throws Exception {
        // 泛型组件 + 声明注解并存:签名与注解都不丢
        String src = ANNOT_IMPORTS + ANN_A + """
                record Rec(@A("g") java.util.List<String> x) {}
                """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "Rec");
        DecompileTestHarness.assertContains(out,
                "record Rec(@A(\"g\") List<String> x)");
        DecompileTestHarness.assertRecompiles(out, "Rec",
                Map.of("A", ANNOT_IMPORTS + ANN_A));
    }

    @Test
    public void testTypeUseOnlyComponentAnnotation() throws Exception {
        // 仅 TYPE_USE 目标的注解不落声明注解,存类型根路径,须从类型注解取
        String src = ANNOT_IMPORTS + ANN_T + """
                record Rec(@T String x) {}
                """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "Rec");
        DecompileTestHarness.assertContains(out, "record Rec(@T String x)");
        DecompileTestHarness.assertRecompiles(out, "Rec",
                Map.of("T", ANNOT_IMPORTS + ANN_T));
    }

    @Test
    public void testRecordWithoutAnnotationsUnaffected() throws Exception {
        // 无注解 record 不受影响
        String out = DecompileTestHarness.decompileWithInnerLoader(
                "record Rec(int x, String y) {}\n",
                "Rec");
        DecompileTestHarness.assertContains(out, "record Rec(int x, String y)");
        DecompileTestHarness.assertRecompiles(out, "Rec", Map.of());
    }
}
