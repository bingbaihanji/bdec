package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 泛型 record + 数组 class 字面量 + for-each 变量名还原往返测试.
 *
 * <p>覆盖 {@code Pair<L,R>} 样例暴露的三个编译错误:</p>
 * <ol>
 *   <li><b>record 类型参数丢失</b>:RecordRewriter 曾把 record 组件塞进
 *       {@code typeParameters} 槽位,泛型参数 {@code <L, R>} 被覆盖丢弃,
 *       输出 {@code record Pair(L left, R right)}.现 typeParameters 与
 *       recordComponents 分离,输出 {@code record Pair<L, R>(...)}.</li>
 *   <li><b>数组 class 字面量</b>:{@code int[].class} 的 CpClass 名为描述符
 *       {@code "[I"},曾原样渲染成非法的 {@code [I.class},且其 VarExpr 被
 *       SourceCleanup 误当未声明变量生成 {@code int [I = 0;}.现解析描述符
 *       渲染 {@code int[].class},isTypeName 识别数组类型名.</li>
 *   <li><b>for-each 变量名错配</b>:ForLoopRecognizer/AstTransformer 用 3 参
 *       构造器重建循环体时丢弃 {@code forEachVar/forEachVarType},循环体仍引用
 *       原变量名 {@code mapValue} 而未声明,且类型擦成 Object.现按循环种类
 *       重建,输出 {@code for (Entry<L, R> mapValue : mapEntry)}.</li>
 * </ol>
 */
public class GenericRecordArrayForEachRoundTripTest {

    @Test
    public void testPairRecordAllFixes() throws Exception {
        String src = """
                     import java.util.*;
                     import java.util.Map.Entry;

                     public record Pair<L, R>(L left, R right) implements Entry<L, R> {

                         public static <L, R> Pair<L, R> of(L left, R right) {
                             return new Pair<>(left, right);
                         }

                         public static <L, R> List<Pair<L, R>> fromEntry(Map<L, R> map) {
                             Set<Entry<L, R>> mapEntry = map.entrySet();
                             List<Pair<L, R>> list = new ArrayList<>();
                             for (Entry<L, R> mapValue : mapEntry) {
                                 Pair<L, R> pair = new Pair<>(mapValue.getKey(), mapValue.getValue());
                                 list.add(pair);
                             }
                             return list;
                         }

                         static String arrayClass(Object value) {
                             Class<?> clazz = value.getClass();
                             if (clazz == int[].class) {
                                 return Arrays.toString((int[]) value);
                             }
                             if (clazz == long[].class) {
                                 return Arrays.toString((long[]) value);
                             }
                             return clazz == String[].class
                                     ? Arrays.toString((String[]) value) : "other";
                         }

                         @Override
                         public L getKey() { return left; }

                         @Override
                         public R getValue() { return right; }

                         @Override
                         public R setValue(R value) { throw new UnsupportedOperationException(); }
                     }
                     """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "Pair");
        // record 类型参数保留
        DecompileTestHarness.assertContains(out,
                "record Pair<L, R>(L left, R right)");
        // 数组 class 字面量正确渲染,无伪声明.
        // 末个 if-else 可能被 TernaryRewriter 折叠为 != 形式,只断言类型名.
        DecompileTestHarness.assertContains(out,
                "clazz == int[].class",
                "clazz == long[].class",
                "String[].class");
        DecompileTestHarness.assertNotContains(out,
                "[I.class", "int [I = 0;", "[Ljava.lang.String;");
        // for-each 迭代变量名与类型还原:变量声明为 mapValue,体引用同名可用
        DecompileTestHarness.assertContains(out,
                "for (Entry<L, R> mapValue : mapEntry)",
                "mapValue.getKey()");
        DecompileTestHarness.assertNotContains(out,
                "for (Object element : mapEntry)");
        DecompileTestHarness.assertRecompiles(out, "Pair", Map.of());
    }

    @Test
    public void testArrayClassLiteralNoBogusDecl() throws Exception {
        // 最小样例:仅数组 class 字面量比较
        String src = """
                     class ArrayLit {
                         static String typeOf(Object o) {
                             Class<?> c = o.getClass();
                             if (c == byte[].class) {
                                 return "byte[]";
                             }
                             if (c == char[][].class) {
                                 return "char[][]";
                             }
                             return "other";
                         }
                     }
                     """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "ArrayLit");
        DecompileTestHarness.assertContains(out,
                "c == byte[].class",
                "char[][].class");
        DecompileTestHarness.assertNotContains(out,
                "[B.class", "[C.class", "int [", "= 0;");
        DecompileTestHarness.assertRecompiles(out, "ArrayLit", Map.of());
    }
}
