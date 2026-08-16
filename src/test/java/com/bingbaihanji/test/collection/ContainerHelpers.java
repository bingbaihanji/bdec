package com.bingbaihanji.test.collection;

import java.util.Arrays;
import java.util.Objects;

/**
 * ArrayMap 底层工具类
 *
 * <p>
 * 提供:
 * <ul>
 * <li>二分查找</li>
 * <li>空安全对象比较</li>
 * <li>容量增长计算</li>
 * </ul>
 *
 * <p>
 * 主要供 ArrayMap / ArraySet 内部使用; 其中 {@link #binarySearch(int[], int, int)} 同时被 structure 包的
 * Sparse* 系列复用,故对外公开
 *
 * @author bingbaihanji
 * @since 1.0
 */
public final class ContainerHelpers {

    /**
     * 默认最小容量
     */
    static final int BASE_SIZE = 4;

    /**
     * 禁止实例化
     */
    private ContainerHelpers() {
        throw new AssertionError("No instances.");
    }

    /**
     * 对 int 数组进行二分查找
     *
     * <p>
     * 返回值与 {@link Arrays#binarySearch(int[], int, int, int)} 完全一致:
     *
     * <pre>
     * >=0   找到元素索引
     * <0    -(插入位置)-1
     * </pre>
     * @param array 数组
     * @param size 有效元素数量
     * @param value 查找值
     * @return 查找结果
     */
    public static int binarySearch(int[] array, int size, int value) {
        return Arrays.binarySearch(array, 0, size, value);
    }

    /**
     * 空安全对象比较
     * @param a 对象A
     * @param b 对象B
     * @return 是否相等
     */
    static boolean equal(Object a, Object b) {
        return Objects.equals(a, b);
    }

    /**
     * 根据当前容量计算下一次扩容后的建议容量
     *
     * <p>
     * 增长策略(入参为当前容量,返回扩容后的新容量):
     *
     * <pre>
     * ≤ 0    -> 4              (BASE_SIZE,首次分配)
     * 1 ~ 7  -> 8
     * ≥ 8    -> 当前容量 × 1.5  (向下取整)
     * </pre>
     *
     * <p>
     * Android 原版按 {@code 4,8,12,18......} 固定步长增长; JVM 上改用 1.5 倍增长,可在批量写入时摊薄数组复制次数
     * @param currentCapacity 当前容量
     * @return 扩容后的新容量
     */
    static int growSize(int currentCapacity) {

        if (currentCapacity <= 0) {
            return BASE_SIZE;
        }

        if (currentCapacity < 8) {
            return 8;
        }

        return currentCapacity + (currentCapacity >> 1);
    }

    /**
     * 判断索引是否合法
     * @param index 索引
     * @param size 当前大小
     */
    static void checkIndex(int index, int size) {

        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    /**
     * 判断容量是否合法
     * @param capacity 容量
     */
    static void checkCapacity(int capacity) {

        if (capacity < 0) {
            throw new IllegalArgumentException("Illegal capacity: " + capacity);
        }
    }

    /**
     * 判断两个 hash 是否相同
     *
     * <p>
     * 单独抽出,后续可方便修改 hash 策略
     * @param h1 hash1
     * @param h2 hash2
     * @return 是否相同
     */
    static boolean sameHash(int h1, int h2) {
        return h1 == h2;
    }

    /**
     * 获取对象 hash
     *
     * null 返回 0
     * @param obj 对象
     * @return hash
     */
    static int hash(Object obj) {
        return obj == null ? 0 : obj.hashCode();
    }

    /**
     * 将 key 存放在 Object[] 中的位置
     *
     * 每个 Entry 占两个槽:
     *
     * <pre>
     * key0 value0
     * key1 value1
     * key2 value2
     * </pre>
     * @param entryIndex Entry 下标
     * @return key 在数组中的位置
     */
    static int keyIndex(int entryIndex) {
        return entryIndex << 1;
    }

    /**
     * value 在 Object[] 中的位置
     * @param entryIndex Entry 下标
     * @return value 下标
     */
    static int valueIndex(int entryIndex) {
        return (entryIndex << 1) + 1;
    }

}