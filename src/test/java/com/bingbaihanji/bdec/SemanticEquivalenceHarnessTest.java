package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * {@link SemanticEquivalenceHarness} 单元 + 负面 smoke 测试.
 *
 * <p>证明执行级 harness 能抓住"能编译但行为错"的静默语义错误:对三个已知旧 bug
 * 的等价反编译产物(注入的 buggy 源码,可编译但行为不同),断言 harness 抛
 * AssertionError;对当前 BDEC 真实反编译输出,断言通过。一正一负证明 harness
 * 拒绝错误实现、接受正确实现。</p>
 */
public class SemanticEquivalenceHarnessTest {

    private static final String BASIC_SRC =
            "class Basic {\n"
            + "    public static void main(String[] args) {\n"
            + "        System.out.println(\"hi\");\n"
            + "    }\n"
            + "}\n";

    // ---------- 正向:harness 管线基础可跑通 ----------

    @Test
    public void testBasicPipeline() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalent(BASIC_SRC, "Basic");
    }

    // ---------- 样例 1:三元作操作数 ----------

    private static final String TERNARY_AS_OPERAND_SRC =
            "class TernaryAsOperandCheck {\n"
            + "    static int cond(int a, int b, int c, int d, int e) {\n"
            + "        return (a > 0 ? b : c) > 0 ? d : e;\n"
            + "    }\n"
            + "    public static String check() {\n"
            + "        return \"v=\" + cond(-1, -5, 3, 10, 20) + \";u=\" + cond(1, 2, -1, 10, 20);\n"
            + "    }\n"
            + "    public static void main(String[] args) { System.out.println(check()); }\n"
            + "}\n";

    /** 旧 bug 等价物:三元条件被压平为 b<=0?e:d,丢 c、错用 b. */
    private static final String TERNARY_AS_OPERAND_BUGGY =
            "class TernaryAsOperandCheck {\n"
            + "    static int cond(int a, int b, int c, int d, int e) {\n"
            + "        return b <= 0 ? e : d;\n"
            + "    }\n"
            + "    public static String check() {\n"
            + "        return \"v=\" + cond(-1, -5, 3, 10, 20) + \";u=\" + cond(1, 2, -1, 10, 20);\n"
            + "    }\n"
            + "    public static void main(String[] args) { System.out.println(check()); }\n"
            + "}\n";

    @Test
    public void testTernaryAsOperandPositive() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalent(TERNARY_AS_OPERAND_SRC, "TernaryAsOperandCheck");
    }

    @Test(expected = AssertionError.class)
    public void testTernaryAsOperandNegative() throws Exception {
        SemanticEquivalenceHarness.assertRecompiledSemantics(
                TERNARY_AS_OPERAND_SRC, "TernaryAsOperandCheck", TERNARY_AS_OPERAND_BUGGY, Map.of());
    }

    // ---------- 样例 2:复合赋值 + 三元 ----------

    private static final String COMPOUND_ASSIGN_SRC =
            "class TernaryCompoundAssignCheck {\n"
            + "    static int sum(int[] arr) {\n"
            + "        int s = 0;\n"
            + "        for (int i = 0; i < arr.length; i++) {\n"
            + "            s += arr[i] > 0 ? 1 : 0;\n"
            + "        }\n"
            + "        return s;\n"
            + "    }\n"
            + "    public static String check() {\n"
            + "        return \"s=\" + sum(new int[] {1, -1, 2});\n"
            + "    }\n"
            + "    public static void main(String[] args) { System.out.println(check()); }\n"
            + "}\n";

    /** 旧 bug 等价物:退化为无条件自增,恒加一. */
    private static final String COMPOUND_ASSIGN_BUGGY =
            "class TernaryCompoundAssignCheck {\n"
            + "    static int sum(int[] arr) {\n"
            + "        int s = 0;\n"
            + "        for (int i = 0; i < arr.length; i++) {\n"
            + "            s++;\n"
            + "        }\n"
            + "        return s;\n"
            + "    }\n"
            + "    public static String check() {\n"
            + "        return \"s=\" + sum(new int[] {1, -1, 2});\n"
            + "    }\n"
            + "    public static void main(String[] args) { System.out.println(check()); }\n"
            + "}\n";

    @Test
    public void testCompoundAssignPositive() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalent(COMPOUND_ASSIGN_SRC, "TernaryCompoundAssignCheck");
    }

    @Test(expected = AssertionError.class)
    public void testCompoundAssignNegative() throws Exception {
        SemanticEquivalenceHarness.assertRecompiledSemantics(
                COMPOUND_ASSIGN_SRC, "TernaryCompoundAssignCheck", COMPOUND_ASSIGN_BUGGY, Map.of());
    }

    // ---------- 样例 3:finally 去重副作用 ----------

    private static final String FINALLY_SIDE_EFFECT_SRC =
            "class FinallySideEffectCheck {\n"
            + "    static int x;\n"
            + "    static int m() {\n"
            + "        try {\n"
            + "            return 1;\n"
            + "        } finally {\n"
            + "            x++;\n"
            + "        }\n"
            + "    }\n"
            + "    public static String check() {\n"
            + "        x = 0;\n"
            + "        int r = m();\n"
            + "        return \"r=\" + r + \";x=\" + x;\n"
            + "    }\n"
            + "    public static void main(String[] args) { System.out.println(check()); }\n"
            + "}\n";

    /** 旧 bug 等价物:finally 体未去重,内联进 try 体执行两次. */
    private static final String FINALLY_SIDE_EFFECT_BUGGY =
            "class FinallySideEffectCheck {\n"
            + "    static int x;\n"
            + "    static int m() {\n"
            + "        x++;\n"
            + "        x++;\n"
            + "        return 1;\n"
            + "    }\n"
            + "    public static String check() {\n"
            + "        x = 0;\n"
            + "        int r = m();\n"
            + "        return \"r=\" + r + \";x=\" + x;\n"
            + "    }\n"
            + "    public static void main(String[] args) { System.out.println(check()); }\n"
            + "}\n";

    @Test
    public void testFinallySideEffectPositive() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalent(FINALLY_SIDE_EFFECT_SRC, "FinallySideEffectCheck");
    }

    @Test(expected = AssertionError.class)
    public void testFinallySideEffectNegative() throws Exception {
        SemanticEquivalenceHarness.assertRecompiledSemantics(
                FINALLY_SIDE_EFFECT_SRC, "FinallySideEffectCheck", FINALLY_SIDE_EFFECT_BUGGY, Map.of());
    }
}
