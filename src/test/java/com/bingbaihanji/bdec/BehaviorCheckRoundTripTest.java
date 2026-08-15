package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 执行级语义等价测试套件:15 个行为样例.
 *
 * <p>每个样例是 {@code src/test/resources/behavior-samples/} 下的自包含 Java 源
 * ({@code public static String check()} + {@code main} 打印确定性输出)。harness
 * 编译原始源码 → BDEC 反编译 → 重编译反编译产物 → 用同一输入运行两份,比对
 * 退出码与 stdout。捕获"能编译但行为错"的静默语义错误。</p>
 */
public class BehaviorCheckRoundTripTest {

    @Test(timeout = 120_000)
    public void testTernaryAsOperand() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("TernaryAsOperandCheck");
    }

    @Test(timeout = 120_000)
    public void testTernaryCompoundAssign() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("TernaryCompoundAssignCheck");
    }

    @Test(timeout = 120_000)
    public void testNestedTernary() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("NestedTernaryCheck");
    }

    @Test(timeout = 120_000)
    public void testTernaryReturnMerge() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("TernaryReturnMergeCheck");
    }

    @Test(timeout = 120_000)
    public void testLoopBoundIncrement() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("LoopBoundIncrementCheck");
    }

    @Test(timeout = 120_000)
    public void testSwitchExpr() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("SwitchExprCheck");
    }

    @Test(timeout = 120_000)
    public void testEnumSwitch() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("EnumSwitchCheck");
    }

    @Test(timeout = 120_000)
    public void testStringSwitch() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("StringSwitchCheck");
    }

    @Test(timeout = 120_000)
    public void testFinallySideEffect() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("FinallySideEffectCheck");
    }

    @Test(timeout = 120_000)
    public void testTryWithResources() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("TryWithResourcesCheck");
    }

    @Test(timeout = 120_000)
    public void testBoxing() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("BoxingCheck");
    }

    @Test(timeout = 120_000)
    public void testStringConcat() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("StringConcatCheck");
    }

    @Test(timeout = 120_000)
    public void testLambdaCapture() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("LambdaCaptureCheck");
    }

    @Test(timeout = 120_000)
    public void testBooleanShortCircuit() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("BooleanShortCircuitCheck");
    }

    @Test(timeout = 120_000)
    public void testGenericCast() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("GenericCastCheck");
    }

    @Test(timeout = 120_000)
    public void testEnumBody() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("EnumBodyCheck");
    }

    @Test(timeout = 120_000)
    public void testInnerClassThis0() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("InnerClassCheck");
    }

    @Test(timeout = 120_000)
    public void testDoWhile() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("DoWhileCheck");
    }

    @Test(timeout = 120_000)
    public void testNestedBreakContinue() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("NestedBreakContinueCheck");
    }

    @Test(timeout = 120_000)
    public void testSwitchFallthrough() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("SwitchFallthroughCheck");
    }

    @Test(timeout = 120_000)
    public void testMultiCatch() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("MultiCatchCheck");
    }

    @Test(timeout = 120_000)
    public void testArray2d() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("Array2dCheck");
    }

    @Test(timeout = 120_000)
    public void testBitOps() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("BitOpsCheck");
    }

    @Test(timeout = 120_000)
    public void testStringMethods() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("StringMethodsCheck");
    }

    @Test(timeout = 120_000)
    public void testGenericMethod() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("GenericMethodCheck");
    }

    @Test(timeout = 120_000)
    public void testRecursion() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("RecursionCheck");
    }

    @Test(timeout = 120_000)
    public void testSynchronized() throws Exception {
        SemanticEquivalenceHarness.assertSemanticallyEquivalentResource("SynchronizedCheck");
    }
}
