package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * MapUtils 泛型 lambda 回归往返测试.
 *
 * <p>锁定两类静默错误:</p>
 * <ol>
 *   <li><b>布尔短路嵌套 if 折叠</b>:javac 把 filter 谓词的
 *       {@code return !(value instanceof String && ((String) value).isEmpty())}
 *       编译为共享假目标的嵌套 if,javac 在前一提交已折叠顶层短路,但分支体内的
 *       嵌套 if 走 translateBranchGroup 未应用折叠,输出成缺少尾部 return 的
 *       嵌套 if + {@code 1}/{@code 0} int 字面量,lambda 无法编译(且连累
 *       {@code toMap} 类型推断级联失败).</li>
 *   <li><b>类型变量擦除强转</b>:lambda 合成方法内类型变量 K 不在作用域,
 *       javac 把 {@code result.put(e.getKey(), ...)} 发射为 {@code checkcast
 *       Comparable}(K 的擦除).内联回 {@code <K extends Comparable<? super K>, V>}
 *       方法后,{@code (Comparable) e.getKey()} 无法赋给 put 的 {@code K} 参数.
 *       RedundantCastRewriter 识别"实参强转到擦除类 + 接收者该位置类型实参为同一
 *       类型变量"后重定向为 {@code (K)}.</li>
 * </ol>
 */
public class MapUtilsRoundTripTest {

    @Test
    public void testFilterPredicateNestedShortCircuit() throws Exception {
        String src = """
                     import java.util.Map;
                     import java.util.LinkedHashMap;
                     import java.util.stream.Collectors;
                     public class FilterPredicate {
                         public static Map<String, Object> filter(Map<String, Object> map) {
                             return map.entrySet().stream().filter(entry -> {
                                 Object value = entry.getValue();
                                 if (value == null) {
                                     return false;
                                 }
                                 return !(value instanceof String && ((String) value).isEmpty());
                             }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v2, LinkedHashMap::new));
                         }
                     }
                     """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "FilterPredicate");
        // 折叠为单一布尔 return:不再有缺失 return 的嵌套 if 与 int 字面量
        DecompileTestHarness.assertContains(out,
                "!(value instanceof String) || !((String) value).isEmpty()");
        DecompileTestHarness.assertNotContains(out, "? 1 :");
        DecompileTestHarness.assertNotContains(out, "? 0 : 1");
        DecompileTestHarness.assertRecompiles(out, "FilterPredicate", java.util.Map.of());
    }

    @Test
    public void testBoundedTypeVarErasureCast() throws Exception {
        String src = """
                     import java.util.Comparator;
                     import java.util.LinkedHashMap;
                     import java.util.Map;
                     public class BoundedKeySort {
                         public static <K extends Comparable<? super K>, V> Map<K, V> sortMapByKey(Map<K, V> data) {
                             LinkedHashMap<K, V> result = new LinkedHashMap<>();
                             data.entrySet().stream()
                                     .sorted(Map.Entry.comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
                                     .forEach(e -> result.put(e.getKey(), e.getValue()));
                             return result;
                         }
                     }
                     """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "BoundedKeySort");
        // 类型变量擦除强转重定向为 (K);不得残留无法赋给 K 的 (Comparable)
        DecompileTestHarness.assertContains(out, "result.put((K) e.getKey(), e.getValue())");
        DecompileTestHarness.assertNotContains(out, "put((Comparable) e.getKey()");
        DecompileTestHarness.assertRecompiles(out, "BoundedKeySort", java.util.Map.of());
    }
}
