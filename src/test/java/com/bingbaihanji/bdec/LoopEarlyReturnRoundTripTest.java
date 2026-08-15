package com.bingbaihanji.bdec;

import org.junit.Test;

import java.util.Map;

/**
 * 循环体内方法返回的往返测试.
 *
 * <p>for-each/while 内 {@code if (cond) return e;} 的出口是方法 RETURN 而非
 * 普通循环 break——LoopTranslator 此前把任何出口一律当 {@code break},
 * 返回值丢失 + 后续引用自动补 {@code int e = 0} 无法重编译.修复:
 * {@code leadsOnlyToReturn} 检测出口为 RETURN 时翻译区域产生 return 语句.</p>
 */
public class LoopEarlyReturnRoundTripTest {

    @Test
    public void testForEachEarlyReturn() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                import java.util.List;
                class FE {
                    static String find(List<String> items, String key) {
                        for (String item : items) {
                            if (item.equals(key)) return item;
                        }
                        return null;
                    }
                }
                """,
                "FE");
        DecompileTestHarness.assertContains(out,
                "if (item.equals(key))",
                "return item;",
                "return null;");
        DecompileTestHarness.assertNotContains(out, "break;");
        DecompileTestHarness.assertNotContains(out, "int item = 0");
        DecompileTestHarness.assertRecompiles(out, "FE", Map.of());
    }

    @Test
    public void testWhileEarlyReturn() throws Exception {
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                class WH {
                    static int firstPos(int[] arr, int target) {
                        int i = 0;
                        while (i < arr.length) {
                            if (arr[i] == target) return i;
                            i++;
                        }
                        return -1;
                    }
                }
                """,
                "WH");
        DecompileTestHarness.assertContains(out,
                "if (arr[i] == target)",
                "return i;",
                "return -1;");
        DecompileTestHarness.assertNotContains(out, "break;");
        DecompileTestHarness.assertRecompiles(out, "WH", Map.of());
    }

    @Test
    public void testLoopNormalBreakUnaffected() throws Exception {
        // 普通 break 仍保持(非 return)
        String out = DecompileTestHarness.decompileWithInnerLoader(
                """
                class LB {
                    static int sumUntil(List<Integer> items, int limit) {
                        int s = 0;
                        for (int n : items) {
                            if (n > limit) break;
                            s += n;
                        }
                        return s;
                    }
                }
                """.replace("List<Integer>", "java.util.List<Integer>"),
                "LB");
        DecompileTestHarness.assertContains(out, "break;");
        DecompileTestHarness.assertRecompiles(out, "LB", Map.of());
    }
}
