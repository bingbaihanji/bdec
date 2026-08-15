package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 菱形推断重建往返测试.
 *
 * <p>字节码的 NEW 指令天然擦除类型实参,BDEC 无法从字节码恢复源码的类型实参;
 * 但能证明"菱形推断可行"时输出 {@code new ArrayList<>(...)} 让 javac 在重编译
 * 时恢复泛型.三种置位来源:</p>
 * <ul>
 *   <li><b>声明目标类型</b>:{@code List<String> x = new ArrayList<>()}——声明类型带
 *       泛型实参且初始化类本身是泛型类;</li>
 *   <li><b>返回目标类型</b>:{@code return new HashMap<>();}——方法签名返回类型带实参;</li>
 *   <li><b>构造器实参绑定</b>:{@code new ArrayList<>(collection)}——泛型类存在参数
 *       个数匹配且涉及类型变量的构造器,菱形可从实参推断.</li>
 * </ul>
 */
public class DiamondReconstructionRoundTripTest {

    @Test
    public void testDeclarationTargetDiamond() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class DT {
                    static String m() {
                        List<String> x = new ArrayList<>();
                        Map<String, Integer> m = new HashMap<>();
                        x.add("a");
                        m.put("b", 2);
                        return x.get(0) + m.get("b");
                    }
                }
                """,
                "DT");
        DecompileTestHarness.assertContains(out,
                "List<String> x = new ArrayList<>()",
                "Map<String, Integer> m = new HashMap<>()");
        DecompileTestHarness.assertNotContains(out, "new ArrayList()");
        DecompileTestHarness.assertRecompiles(out, "DT", Map.of());
    }

    @Test
    public void testReturnTargetDiamond() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class RT {
                    static List<String> m1() {
                        return new ArrayList<>();
                    }
                    static Map<String, Integer> m2() {
                        return new HashMap<>();
                    }
                }
                """,
                "RT");
        DecompileTestHarness.assertContains(out,
                "return new ArrayList<>()",
                "return new HashMap<>()");
        DecompileTestHarness.assertNotContains(out, "new HashMap()");
        DecompileTestHarness.assertRecompiles(out, "RT", Map.of());
    }

    @Test
    public void testConstructorArgBoundDiamond() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.*;
                public class AT {
                    static List<String> m1() {
                        return new ArrayList<>(List.of("x"));
                    }
                    static Map<String, Integer> m2() {
                        return new HashMap<>(Map.of("a", 1));
                    }
                }
                """,
                "AT");
        DecompileTestHarness.assertContains(out,
                "new ArrayList<>(List.of(\"x\"))",
                "new HashMap<>(Map.of(\"a\"");
        DecompileTestHarness.assertNotContains(out, "new HashMap(Map.of");
        DecompileTestHarness.assertRecompiles(out, "AT", Map.of());
    }

    @Test
    public void testNonGenericClassNoDiamond() throws Exception {
        // 非泛型类不加 <>
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                public class NG {
                    static StringBuilder m() {
                        return new StringBuilder("x");
                    }
                }
                """,
                "NG");
        DecompileTestHarness.assertContains(out, "new StringBuilder(\"x\")");
        DecompileTestHarness.assertNotContains(out, "new StringBuilder<>");
        DecompileTestHarness.assertRecompiles(out, "NG", Map.of());
    }
}
