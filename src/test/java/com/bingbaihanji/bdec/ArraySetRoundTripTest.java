package com.bingbaihanji.bdec;

import org.junit.Test;

/**
 * ArraySet 反编译回归往返测试.
 *
 * <p>锁定五类静默/编译错误:</p>
 * <ol>
 *   <li><b>循环携带布尔标志</b>:{@code boolean changed = false; for(...) changed |= add(); return changed;}
 *       字节码按 int 0/1 存储,Inliner 把旧版本读折叠成初值 → {@code return false};声明收窄
 *       (boolean + 字面量转换)与"变量被重新赋值不内联"修复.</li>
 *   <li><b>字段源跨调用内联</b>:{@code int originalSize = this.size; ...remove(); return originalSize != size;}
 *       originalSize 的字段源在 remove() 调用后过期,内联成 {@code size != size} 恒 false;字段源跨
 *       INVOKE 禁止内联修复.</li>
 *   <li><b>循环内终止分支</b>:forEach 的 {@code if (modCount != expected) throw CME} 的 throw 被移出循环
 *       变成无条件执行;blockIsReturn 识别 THROW 修复.</li>
 *   <li><b>数组下标后置自增</b>:迭代器 {@code return (E) array[nextIndex++]} 的字段自增折进数组下标
 *       (否则数组读到自增后的值错位一跳,首元素变 null).</li>
 *   <li><b>接收者类型实参绑定</b>:{@code Predicate<? super E>} 接收者的 {@code test}/{@code accept}
 *       形参 T 绑定为 {@code ? super E},实参强转 {@code (E) array[i]} 而非未定义 {@code (T)}.</li>
 * </ol>
 */
public class ArraySetRoundTripTest {

    @Test
    public void testLoopCarriedBooleanAndBounds() throws Exception {
        String src = """
                import java.util.Collection;
                import java.util.function.Consumer;
                import java.util.function.Predicate;
                import java.util.Iterator;
                import java.util.ConcurrentModificationException;
                public class LoopCarry {
                    private int size;
                    private Object[] array = new Object[8];
                    private int modCount;

                    public boolean addAll(Collection<?> c) {
                        boolean changed = false;
                        for (Object v : c) {
                            changed |= add(v);
                        }
                        return changed;
                    }
                    public boolean add(Object v) {
                        array[size++] = v;
                        modCount++;
                        return true;
                    }
                    public boolean removeAll() {
                        int originalSize = size;
                        for (int i = size - 1; i >= 0; i--) {
                            remove(i);
                        }
                        return originalSize != size;
                    }
                    public void remove(int i) {
                        array[i] = null;
                        size--;
                    }
                    public void forEach(Consumer<? super Object> action) {
                        int expected = modCount;
                        for (int i = 0; i < size; i++) {
                            action.accept(array[i]);
                            if (modCount != expected) {
                                throw new ConcurrentModificationException();
                            }
                        }
                    }
                    public Iterator<Object> iterator() {
                        return new Itr();
                    }
                    private final class Itr implements Iterator<Object> {
                        private int nextIndex;
                        public boolean hasNext() { return nextIndex < size; }
                        public Object next() {
                            if (nextIndex >= size) throw new java.util.NoSuchElementException();
                            return array[nextIndex++];
                        }
                    }
                }
                """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "LoopCarry");
        // 循环携带布尔标志:声明 boolean + return 变量(而非 return false)
        DecompileTestHarness.assertContains(out, "boolean changed = false;",
                "return changed;");
        DecompileTestHarness.assertNotContains(out, "return false;");
        // 字段源跨调用:originalSize != size(而非 size != size)
        DecompileTestHarness.assertContains(out, "return originalSize != size;");
        // 循环内 throw 保留在 if 内
        DecompileTestHarness.assertContains(out,
                "throw new ConcurrentModificationException();");
        // 数组下标后置自增(变量名来自 LVT,名字不固定)
        DecompileTestHarness.assertContains(out, "array[", "++];");
        DecompileTestHarness.assertNotContains(out, "nextIndex++;\n");
        DecompileTestHarness.assertRecompiles(out, "LoopCarry", java.util.Map.of());
    }

    @Test
    public void testWildcardReceiverArgCast() throws Exception {
        String src = """
                import java.util.function.Consumer;
                import java.util.function.Predicate;
                public class WildcardCast {
                    private Object[] array = new Object[8];
                    private int size;
                    public void removeIf(Predicate<? super String> filter) {
                        for (int i = 0; i < size; i++) {
                            if (filter.test((String) array[i])) {
                                array[i] = null;
                            }
                        }
                    }
                    public void forEach(Consumer<? super String> action) {
                        for (int i = 0; i < size; i++) {
                            action.accept((String) array[i]);
                        }
                    }
                }
                """;
        String out = DecompileTestHarness.decompileWithInnerLoader(src, "WildcardCast");
        // 通配符接收者实参强转:test/accept 形参 T 绑为 ? super String → 实参 (String)
        //(变量名不固定,按模式断言;不得出现未定义的 (T))
        DecompileTestHarness.assertContains(out, "(String) array[");
        DecompileTestHarness.assertNotContains(out, "(T) array");
        DecompileTestHarness.assertRecompiles(out, "WildcardCast", java.util.Map.of());
    }
}
