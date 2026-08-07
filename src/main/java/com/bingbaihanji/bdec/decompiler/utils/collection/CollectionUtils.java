package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 集合工具类
 *
 * <p>
 * 提供集合判空,安全取值,排序,集合运算,转换等常用方法,所有方法均对 {@code null} 安全
 */
public final class CollectionUtils {

    /** 不可构造 */
    private CollectionUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    // ==================== 判空 / 大小 ====================

    /**
     * 判断集合是否为 {@code null} 或空
     * @param coll 集合
     * @return 如果为 {@code null} 或没有元素则返回 {@code true}
     */
    public static boolean isEmpty(final Collection<?> coll) {
        return coll == null || coll.isEmpty();
    }

    /**
     * 判断集合是否非空
     * @param coll 集合
     * @return 如果非 {@code null} 且包含元素则返回 {@code true}
     */
    public static boolean isNotEmpty(final Collection<?> coll) {
        return !isEmpty(coll);
    }

    /**
     * 判断 {@link Map} 是否为 {@code null} 或空
     * @param map 映射
     * @return 如果为 {@code null} 或没有键值对则返回 {@code true}
     */
    public static boolean isEmpty(final Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * 判断 {@link Map} 是否非空
     * @param map 映射
     * @return 如果非 {@code null} 且包含键值对则返回 {@code true}
     */
    public static boolean isNotEmpty(final Map<?, ?> map) {
        return !isEmpty(map);
    }

    /**
     * 安全获取集合大小,{@code null} 返回 0
     * @param coll 集合
     * @return 集合大小,若为 {@code null} 返回 0
     */
    public static int size(final Collection<?> coll) {
        return coll == null ? 0 : coll.size();
    }

    /**
     * 安全获取 {@link Map} 大小,{@code null} 返回 0
     * @param map 映射
     * @return 映射大小,若为 {@code null} 返回 0
     */
    public static int size(final Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }

    // ==================== 空安全返回 ====================

    /**
     * 如果集合为 {@code null} 则返回空列表,否则返回原集合
     * @param coll 集合
     * @param <T> 元素类型
     * @return 非 {@code null} 的集合
     */
    public static <T> Collection<T> emptyIfNull(final Collection<T> coll) {
        return coll == null ? Collections.emptyList() : coll;
    }

    /**
     * 如果列表为 {@code null} 则返回空列表,否则返回原列表
     * @param list 列表
     * @param <T> 元素类型
     * @return 非 {@code null} 的列表
     */
    public static <T> List<T> emptyIfNull(final List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 如果集合为 {@code null} 则返回空集,否则返回原集合
     * @param set 集合
     * @param <T> 元素类型
     * @return 非 {@code null} 的集合
     */
    public static <T> Set<T> emptyIfNull(final Set<T> set) {
        return set == null ? Collections.emptySet() : set;
    }

    // ==================== 不可变集合快捷创建 ====================

    /**
     * 创建不可变列表
     * @param items 元素
     * @param <T> 元素类型
     * @return 不可变列表
     */
    @SafeVarargs
    public static <T> List<T> unmodifiableList(final T... items) {
        return Collections.unmodifiableList(Arrays.asList(items));
    }

    /**
     * 创建不可变集合
     * @param items 元素
     * @param <T> 元素类型
     * @return 不可变集合
     */
    @SafeVarargs
    public static <T> Set<T> unmodifiableSet(final T... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }

    // ==================== 排序相关(原有逻辑保留) ====================

    /**
     * 如果集合非空则排序(防止当一个不可变空列表被多次返回时, 在一个线程中排序而在另一个线程中遍历时抛出
     * {@link ConcurrentModificationException} -- #334)
     * @param <T> 元素类型
     * @param list 列表
     */
    public static <T extends Comparable<? super T>> void sortIfNotEmpty(final List<T> list) {
        if (list != null && list.size() > 1) {
            list.sort(null);
        }
    }

    /**
     * 如果集合非空则排序(防止当一个不可变空列表被多次返回时, 在一个线程中排序而在另一个线程中遍历时抛出
     * {@link ConcurrentModificationException} -- #334)
     * @param <T> 元素类型
     * @param list 列表
     * @param comparator 比较器
     */
    public static <T> void sortIfNotEmpty(final List<T> list, final Comparator<? super T> comparator) {
        if (list != null && list.size() > 1) {
            list.sort(comparator);
        }
    }

    /**
     * 复制并排序集合
     * @param elts 要复制并排序的集合
     * @param <T> 元素类型
     * @return 排序后的集合副本
     */
    public static <T extends Comparable<T>> List<T> sortCopy(final Collection<T> elts) {
        if (elts == null) {
            return new ArrayList<>();
        }
        final List<T> sortedCopy = new ArrayList<>(elts);
        if (sortedCopy.size() > 1) {
            sortedCopy.sort(null);
        }
        return sortedCopy;
    }

    // ==================== 集合运算 ====================

    /**
     * 求并集(元素可重复)
     * @param a 集合A
     * @param b 集合B
     * @param <T> 元素类型
     * @return 包含A和B所有元素的新列表
     */
    public static <T> List<T> union(final Collection<T> a, final Collection<T> b) {
        final List<T> result = new ArrayList<>(size(a) + size(b));
        if (a != null) {
            result.addAll(a);
        }
        if (b != null) {
            result.addAll(b);
        }
        return result;
    }

    /**
     * 求交集
     * @param a 集合A
     * @param b 集合B
     * @param <T> 元素类型
     * @return 两个集合的交集新列表
     */
    public static <T> List<T> intersection(final Collection<T> a, final Collection<T> b) {
        if (isEmpty(a) || isEmpty(b)) {
            return new ArrayList<>();
        }
        final List<T> result = new ArrayList<>(a);
        result.retainAll(b);
        return result;
    }

    /**
     * 求差集(A - B)
     * @param a 集合A
     * @param b 集合B
     * @param <T> 元素类型
     * @return 在A中但不在B中的元素组成的新列表
     */
    public static <T> List<T> subtract(final Collection<T> a, final Collection<T> b) {
        if (isEmpty(a)) {
            return new ArrayList<>();
        }
        final List<T> result = new ArrayList<>(a);
        if (b != null) {
            result.removeAll(b);
        }
        return result;
    }

    /**
     * 判断集合 a 是否包含集合 b 中的任意一个元素
     * @param a 源集合
     * @param b 目标集合
     * @return 如果 a 包含 b 中的任意元素则返回 {@code true}
     */
    public static boolean containsAny(final Collection<?> a, final Collection<?> b) {
        if (isEmpty(a) || isEmpty(b)) {
            return false;
        }
        // 使用较小的集合迭代以提高性能
        final Collection<?> smaller = a.size() <= b.size() ? a : b;
        final Collection<?> larger = a.size() <= b.size() ? b : a;
        for (final Object item : smaller) {
            if (larger.contains(item)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 提取元素 ====================

    /**
     * 获取集合的第一个元素,若为空则返回 {@code null}
     * @param coll 集合
     * @param <T> 元素类型
     * @return 第一个元素,或 {@code null}
     */
    public static <T> T first(final Collection<T> coll) {
        if (isEmpty(coll)) {
            return null;
        }
        if (coll instanceof List) {
            return ((List<T>) coll).get(0);
        }
        return coll.iterator().next();
    }

    /**
     * 获取列表的最后一个元素,若为空则返回 {@code null}
     * @param list 列表
     * @param <T> 元素类型
     * @return 最后一个元素,或 {@code null}
     */
    public static <T> T last(final List<T> list) {
        if (isEmpty(list)) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    /**
     * 随机获取集合中的一个元素
     * @param coll 集合
     * @param <T> 元素类型
     * @return 随机元素,若集合为空则返回 {@code null}
     */
    public static <T> T random(final Collection<T> coll) {
        if (isEmpty(coll)) {
            return null;
        }
        int index = new Random().nextInt(coll.size());
        if (coll instanceof List) {
            return ((List<T>) coll).get(index);
        }
        // 对于非 List 集合,通过迭代器定位
        int i = 0;
        for (final T item : coll) {
            if (i == index) {
                return item;
            }
            i++;
        }
        // 理论上不会走到这里
        return null;
    }

    // ==================== 转换 / 过滤 / 去重 ====================

    /**
     * 将集合转换为列表,对 {@code null} 返回空列表
     * @param iterable 可迭代对象
     * @param <T> 元素类型
     * @return 包含所有元素的列表
     */
    public static <T> List<T> toList(final Iterable<T> iterable) {
        if (iterable == null) {
            return new ArrayList<>();
        }
        if (iterable instanceof Collection) {
            return new ArrayList<>((Collection<T>) iterable);
        }
        return StreamSupport.stream(iterable.spliterator(), false).collect(Collectors.toList());
    }

    /**
     * 过滤集合,返回满足条件的新列表
     * @param coll 源集合
     * @param predicate 过滤条件
     * @param <T> 元素类型
     * @return 过滤后的列表
     */
    public static <T> List<T> filter(final Collection<T> coll, final Predicate<? super T> predicate) {
        if (isEmpty(coll)) {
            return new ArrayList<>();
        }
        return coll.stream().filter(predicate).collect(Collectors.toList());
    }

    /**
     * 转换集合元素类型,返回新列表
     * @param coll 源集合
     * @param function 转换函数
     * @param <T> 源类型
     * @param <R> 目标类型
     * @return 转换后的列表
     */
    public static <T, R> List<R> collect(final Collection<T> coll, final Function<? super T, ? extends R> function) {
        if (isEmpty(coll)) {
            return new ArrayList<>();
        }
        return coll.stream().map(function).collect(Collectors.toList());
    }

    /**
     * 去除重复元素(保持顺序)
     * @param coll 源集合
     * @param <T> 元素类型
     * @return 去重后的列表
     */
    public static <T> List<T> distinct(final Collection<T> coll) {
        if (isEmpty(coll)) {
            return new ArrayList<>();
        }
        return coll.stream().distinct().collect(Collectors.toList());
    }

    // ==================== 分区 / 打乱 ====================

    /**
     * 将列表按指定大小分区
     * @param list 源列表
     * @param size 每个分区的大小,必须 &gt;  0
     * @param <T> 元素类型
     * @return 分区后的列表,每个元素为一个子列表
     * @throws IllegalArgumentException 如果 size &lt; = 0
     */
    public static <T> List<List<T>> partition(final List<T> list, final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("分区大小必须大于 0");
        }
        if (isEmpty(list)) {
            return new ArrayList<>();
        }
        final List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return partitions;
    }

    /**
     * 随机打乱列表,返回新列表,不影响原列表
     * @param list 源列表
     * @param <T> 元素类型
     * @return 打乱顺序后的新列表
     */
    public static <T> List<T> shuffle(final List<T> list) {
        if (isEmpty(list)) {
            return new ArrayList<>();
        }
        final List<T> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled);
        return shuffled;
    }

    /**
     * 使用指定随机源随机打乱列表,返回新列表,不影响原列表
     * @param list 源列表
     * @param rnd 随机生成器
     * @param <T> 元素类型
     * @return 打乱顺序后的新列表
     */
    public static <T> List<T> shuffle(final List<T> list, final Random rnd) {
        if (isEmpty(list)) {
            return new ArrayList<>();
        }
        final List<T> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled, rnd);
        return shuffled;
    }

    // ==================== 合并多个集合 ====================

    /**
     * 将多个列表合并为一个新列表
     * @param lists 多个列表
     * @param <T> 元素类型
     * @return 合并后的列表
     */
    @SafeVarargs
    public static <T> List<T> concat(final List<T>... lists) {
        if (lists == null) {
            return new ArrayList<>();
        }
        final List<T> result = new ArrayList<>();
        for (final List<T> list : lists) {
            if (list != null) {
                result.addAll(list);
            }
        }
        return result;
    }

}