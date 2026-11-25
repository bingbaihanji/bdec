package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * 泛型接口自引用方法返回类型还原 + 成员空行分隔往返测试.
 *
 * <p>回归场景:{@code Cache<K,V>} 接口的 {@code get}/{@code put} 字节码签名
 * 被擦除为 {@code Object},BDEC 此前把 {@code this.get(key)} 返回类型写成
 * {@code Object},导致 {@code V existing = this.get(key);} 被输出成
 * {@code Object existing = ...},三目返回 {@code Object} 无法赋给 {@code V}
 * 编译失败.修复后从类 Signature 属性还原 {@code (TK;)TV;} → 返回 {@code V},
 * 变量声明随之收窄,输出可重编译.</p>
 *
 * <p>连带锁定成员间空行分隔(对齐 vineflower 输出风格).</p>
 */
public class GenericInterfaceReturnRoundTripTest {

    @Test
    public void testSelfGenericInterfaceReturnType() throws Exception {
        String src = """
                     public interface Cache<K, V> {
                         V get(K key);
                         V put(K key, V value);
                         V remove(K key);
                         int size();
                         void clear();
                         double hitRate();
                         default boolean containsKey(K key) {
                             return this.get(key) != null;
                         }
                         default V putIfAbsent(K key, V value) {
                             V existing = this.get(key);
                             return existing == null ? this.put(key, value) : existing;
                         }
                     }
                     """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "Cache");
        // 泛型返回类型从 Signature 属性还原:get/put 返回 V 而非 Object
        DecompileTestHarness.assertContains(out,
                "V existing = this.get(key);",
                "return existing != null ? existing : this.put(key, value);");
        DecompileTestHarness.assertNotContains(out, "Object existing");
        DecompileTestHarness.assertRecompiles(out, "Cache", java.util.Map.of());
    }

    @Test
    public void testSelfGenericClassReturnType() throws Exception {
        // 泛型类(非接口)自引用同样应还原返回类型
        String src = """
                     class Box<T> {
                         T value;
                         T get() { return value; }
                         void set(T v) { value = v; }
                         T copy() {
                             T v = this.get();
                             return v;
                         }
                     }
                     """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "Box");
        DecompileTestHarness.assertContains(out,
                "T v = this.get();",
                "return v;");
        DecompileTestHarness.assertRecompiles(out, "Box", java.util.Map.of());
    }

    @Test
    public void testBlankLineBetweenMembers() throws Exception {
        String src = """
                     interface Service {
                         int a();
                         int b();
                         default int c() { return a(); }
                         default int d() { return b(); }
                     }
                     """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "Service");
        // 成员间空行分隔:抽象方法(分号结尾)与方法体结束 '}' 后均空一行
        DecompileTestHarness.assertContains(out,
                "b() ;\n\n    public default int c()",
                "}\n\n    public default int d()");
        DecompileTestHarness.assertRecompiles(out, "Service", java.util.Map.of());
    }
}
