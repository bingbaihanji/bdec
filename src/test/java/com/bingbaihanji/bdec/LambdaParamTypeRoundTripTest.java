package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.emit.ExpressionEmitter;
import com.bingbaihanji.bdec.emit.IndentWriter;
import com.bingbaihanji.bdec.type.JavaType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 第 19 轮:删除 LambdaRewriter.normalizeLambdaParamType(CLASS 伪装的最后一处).
 *
 * <p>发射器规则改为 CLASS 与 TYPE_VARIABLE 都跳过 lambda 参数类型名,
 * 与既有 {@code (t) -> t} 行为一致. javap 实验证实 javac 对显式类型
 * lambda {@code (T t) -> t} 与推断 lambda {@code t -> t} 生成字节级
 * 完全相同的 class 文件(合成方法均无方法级 Signature 属性),因此统一
 * 跳类型名是既定约定,不做显式类型 lambda 的支持.
 */
public class LambdaParamTypeRoundTripTest {

    /** 类级 T 的方法内推断 lambda:参数类型名不输出(不含 "T t ->") */
    @Test
    public void testClassLevelTypeVariableLambdaSkipsParamTypeName() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class C<T> {\n"
                        + "    java.util.function.Function<T, T> m() {\n"
                        + "        return t -> t;\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "return (t) -> t;");
        DecompileTestHarness.assertNotContains(output, "(T t) ->");
    }

    /** 多参数 lambda(参数为 CLASS 类型):本就跳类型名,锁定不回归 */
    @Test
    public void testMultiParamClassTypedLambdaSkipsParamTypeNames() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class C {\n"
                        + "    String m() {\n"
                        + "        java.util.function.BiFunction<String, String, String> f = (a, b) -> a + b;\n"
                        + "        return f.apply(\"x\", \"y\");\n"
                        + "    }\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output, "(a, b) ->");
        DecompileTestHarness.assertNotContains(output, "(String a,");
    }

    /** 发射器直接单测:TYPE_VARIABLE 参数跳类型名(锁定修改后的 648 行规则) */
    @Test
    public void testEmitterSkipsTypeNameForTypeVariableParam() {
        LambdaExpr lambda = LambdaExpr.expression(
                List.of(new LambdaExpr.Param("t", JavaType.typeVariable("T"))),
                new VarExpr("t"), null);
        IndentWriter w = new IndentWriter();
        ExpressionEmitter emitter = new ExpressionEmitter(w);
        emitter.emit(lambda);
        assertEquals("(t) -> t", w.toString());
    }

    /** 发射器直接单测:CLASS 参数同样跳类型名(原规则不回归) */
    @Test
    public void testEmitterSkipsTypeNameForClassParam() {
        LambdaExpr lambda = LambdaExpr.expression(
                List.of(new LambdaExpr.Param("t", JavaType.classType("java/lang/Object"))),
                new VarExpr("t"), null);
        IndentWriter w = new IndentWriter();
        ExpressionEmitter emitter = new ExpressionEmitter(w);
        emitter.emit(lambda);
        assertEquals("(t) -> t", w.toString());
    }

    /** 发射器直接单测:非 CLASS/TYPE_VARIABLE 类型仍写出类型名(规则未被放宽过度) */
    @Test
    public void testEmitterStillWritesTypeNameForOtherKinds() {
        LambdaExpr lambda = LambdaExpr.expression(
                List.of(new LambdaExpr.Param("x", JavaType.INT)), new VarExpr("x"), null);
        IndentWriter w = new IndentWriter();
        ExpressionEmitter emitter = new ExpressionEmitter(w);
        emitter.emit(lambda);
        assertEquals("(int x) -> x", w.toString());
    }
}
