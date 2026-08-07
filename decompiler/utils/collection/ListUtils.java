package com.bingbaihanji.bdec.decompiler.utils.collection;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * List 工具类,提供判空,元素拼接,类型转换,去重,合并等常用操作
 *
 * @author bingbaihanji
 * @date 2026-06-12
 */
public final class ListUtils {

    private ListUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    // 判空

    /**
     * 判断 List 是否为 null 或无元素
     */
    public static boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /**
     * 判断 List 是否非 null 且有元素
     */
    public static boolean isNotEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /**
     * 如果 list 为 null 或空,则返回 defaultList,否则返回原 list
     */
    public static <T> List<T> defaultIfEmpty(List<T> list, List<T> defaultList) {
        return isNotEmpty(list) ? list : defaultList;
    }

    /**
     * 返回不可变的空 List
     */
    public static <T> List<T> emptyList() {
        return Collections.emptyList();
    }

    // 创建

    /**
     * 快速创建包含指定元素的 ArrayList
     */
    @SafeVarargs
    public static <T> List<T> newArrayList(T... items) {
        List<T> list = new ArrayList<>(items.length);
        Collections.addAll(list, items);
        return list;
    }

    /**
     * 将 Iterable 转换为 ArrayList
     */
    public static <T> List<T> toList(Iterable<T> iterable) {
        if (iterable == null) {
            return new ArrayList<>();
        }
        return StreamSupport.stream(iterable.spliterator(), false).collect(Collectors.toCollection(ArrayList::new));
    }

    // 类型转换

    /**
     * 将字符串数组转换为整数列表,自动跳过无法解析的元素
     */
    public static List<Integer> toIntList(String[] arr) {
        if (arr == null) {
            return new ArrayList<>();
        }
        List<Integer> result = new ArrayList<>(arr.length);
        for (String str : arr) {
            if (str != null) {
                try {
                    result.add(Integer.parseInt(str.trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略无法解析的元素
                }
            }
        }
        return result;
    }

    /**
     * 将字符串数组转换为长整数列表,自动跳过无法解析的元素
     */
    public static List<Long> toLongList(String[] arr) {
        if (arr == null) {
            return new ArrayList<>();
        }
        List<Long> result = new ArrayList<>(arr.length);
        for (String str : arr) {
            if (str != null) {
                try {
                    result.add(Long.parseLong(str.trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略无法解析的元素
                }
            }
        }
        return result;
    }

    @Deprecated
    public static Integer[] stringArr2intArr(String[] arr) {
        if (arr == null) {
            return new Integer[0];
        }
        Integer[] result = new Integer[arr.length];
        for (int i = 0; i < arr.length; i++) {
            String str = arr[i];
            if (str != null) {
                try {
                    result[i] = Integer.parseInt(str.trim());
                } catch (NumberFormatException e) {
                    // 保持为 null
                }
            }
        }
        return result;
    }

    // 元素操作

    /**
     * 返回去重后的新 List(借助 LinkedHashSet 保持插入顺序)
     */
    public static <T> List<T> distinct(List<T> list) {
        if (isNullOrEmpty(list)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    /**
     * 返回 List 的第一个元素,如果为空则返回 null
     */
    public static <T> T first(List<T> list) {
        if (isNullOrEmpty(list)) {
            return null;
        }
        return list.getFirst();
    }

    /**
     * 返回 List 的最后一个元素,如果为空则返回 null
     */
    public static <T> T last(List<T> list) {
        if (isNullOrEmpty(list)) {
            return null;
        }
        return list.getLast();
    }

    // 字符串拼接

    /**
     * 将多个对象拼接为逗号分隔的字符串,null 元素会输出 "null"
     */
    public static String toString(Object... objs) {
        return SetUtils.toString(objs);
    }

    /**
     * 将可迭代对象中的所有元素连接为逗号分隔的字符串,null 元素视为空字符串
     */
    public static String join(Iterable<?> iterable) {
        return SetUtils.join(iterable);
    }

    // 合并

    /**
     * 合并多个 List 到一个新的 ArrayList 中(不修改原 List)
     */
    @SafeVarargs
    public static <T> List<T> merge(List<T> first, List<T>... others) {
        List<T> result = new ArrayList<>();
        if (first != null) {
            result.addAll(first);
        }
        if (others != null) {
            for (List<T> other : others) {
                if (other != null) {
                    result.addAll(other);
                }
            }
        }
        return result;
    }

    /**
     * 使用 Supplier 创建新的 List 容器,再将多个 List 合并进去
     */
    @SafeVarargs
    public static <T> List<T> merge(Supplier<List<T>> supplier, List<T> first, List<T>... others) {
        List<T> result = supplier.get();
        if (first != null) {
            result.addAll(first);
        }
        if (others != null) {
            for (List<T> other : others) {
                if (other != null) {
                    result.addAll(other);
                }
            }
        }
        return result;
    }

    /**
     * 创建包含单个元素的可变列表
     * @param obj 初始元素
     * @return 可变列表
     */
    public static <T> List<T> mutableListOf(T obj) {
        List<T> list = new ArrayList<>();
        list.add(obj);
        return list;
    }

    /**
     * 创建包含两个元素的可变列表
     * @param obj1 第一个元素
     * @param obj2 第二个元素
     * @return 可变列表
     */
    public static <T> List<T> mutableListOf(T obj1, T obj2) {
        List<T> list = new ArrayList<>();
        list.add(obj1);
        list.add(obj2);
        return list;
    }

    /**
     * 创建包含指定若干元素的可变列表
     * @param objs 初始元素数组
     * @return 可变列表
     */
    public static <T> List<T> mutableListOf(T... objs) {
        return new ArrayList<>(Arrays.asList(objs));
    }

    /**
     * 判断列表是否恰好只包含一个且等于指定值的元素
     * @param list 待检查的列表
     * @param obj 期望的唯一元素
     * @return 若列表仅含一个元素且等于 obj 则返回 true
     */
    public static <T> boolean isSingleElement(List<T> list, T obj) {
        if (list == null || list.size() != 1) {
            return false;
        }
        return Objects.equals(list.get(0), obj);
    }

    /**
     * 判断两个列表是否包含相同元素 (忽略顺序)
     * @param first 第一个列表
     * @param second 第二个列表
     * @return 若元素集合相同则返回 true
     */
    public static <T> boolean unorderedEquals(List<T> first, List<T> second) {
        if (first.size() != second.size()) {
            return false;
        }
        return new HashSet<>(first).containsAll(second);
    }

    /**
     * 按顺序使用自定义比较器逐一比较两个列表是否相等
     * @param list1 第一个列表
     * @param list2 第二个列表
     * @param comparer 元素比较谓词
     * @return 若长度相同且对应位置元素均通过比较则返回 true
     */
    public static <T, U> boolean orderedEquals(List<T> list1, List<U> list2, BiPredicate<T, U> comparer) {
        if (list1 == list2) {
            return true;
        }
        if (list1.size() != list2.size()) {
            return false;
        }
        Iterator<T> iter1 = list1.iterator();
        Iterator<U> iter2 = list2.iterator();
        while (iter1.hasNext() && iter2.hasNext()) {
            T item1 = iter1.next();
            U item2 = iter2.next();
            if (!comparer.test(item1, item2)) {
                return false;
            }
        }
        return !iter1.hasNext() && !iter2.hasNext();
    }

    /**
     * 将集合的每个元素经映射函数转换后收集为新列表
     * @param list 源集合
     * @param mapFunc 映射函数
     * @return 映射后的新列表 源集合为空时返回空列表
     */
    public static <T, R> List<R> map(Collection<T> list, Function<T, R> mapFunc) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<R> result = new ArrayList<>(list.size());
        for (T t : list) {
            result.add(mapFunc.apply(t));
        }
        return result;
    }

    /**
     * 返回列表的第一个元素,列表为 null 或空时返回 null
     * @param list 列表
     * @return 第一个元素或 null
     */
    public static <T> T firstOrNull(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    /**
     * 移除并返回列表的最后一个元素,列表为 null 或空时返回 null
     * @param list 列表
     * @return 被移除的最后一个元素或 null
     */
    public static <T> T removeLast(List<T> list) {
        if (list == null) {
            return null;
        }
        int size = list.size();
        if (size == 0) {
            return null;
        }
        return list.remove(size - 1);
    }

    /**
     * 合并两个已排序列表并去重,返回有序结果
     * @param first 第一个列表
     * @param second 第二个列表
     * @return 去重后的有序列表 若一方为空则直接返回另一方
     */
    public static <T extends Comparable<T>> List<T> distinctMergeSortedLists(List<T> first, List<T> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        Set<T> set = new TreeSet<>(first);
        set.addAll(second);
        return new ArrayList<>(set);
    }

    /**
     * 对列表去重并保持原有顺序
     * @param list 源列表
     * @return 去重后的新列表
     */
    public static <T> List<T> distinctList(List<T> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    /**
     * 将单个元素与数组拼接为新列表 (元素在前)
     * @param first 首元素
     * @param values 后续元素数组
     * @return 拼接后的新列表
     */
    public static <T> List<T> concat(T first, T[] values) {
        List<T> list = new ArrayList<>(1 + values.length);
        list.add(first);
        list.addAll(Arrays.asList(values));
        return list;
    }

    /**
     * 将列表中的旧元素替换为新元素
     * <p>
     * 支持 null 和由 {@link Collections#emptyList()} 创建的不可变空列表 若未找到旧元素则将新元素追加到列表末尾
     * @param list 源列表
     * @param oldObj 待替换的旧元素
     * @param newObj 新元素
     * @return 替换后的列表
     */
    public static <T> List<T> safeReplace(List<T> list, T oldObj, T newObj) {
        if (list == null || list.isEmpty()) {
            // 不可变空列表
            List<T> newList = new ArrayList<>(1);
            newList.add(newObj);
            return newList;
        }
        int idx = list.indexOf(oldObj);
        if (idx != -1) {
            list.set(idx, newObj);
        } else {
            list.add(newObj);
        }
        return list;
    }

    /**
     * 安全移除列表中的元素 (对 null 或空列表不做处理)
     * @param list 源列表
     * @param obj 待移除的元素
     */
    public static <T> void safeRemove(List<T> list, T obj) {
        if (list != null && !list.isEmpty()) {
            list.remove(obj);
        }
    }

    /**
     * 安全移除元素,若移除后列表为空则返回不可变空列表
     * @param list 源列表
     * @param obj 待移除的元素
     * @return 处理后的列表
     */
    public static <T> List<T> safeRemoveAndTrim(List<T> list, T obj) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        if (list.remove(obj)) {
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
        }
        return list;
    }

    /**
     * 安全地向列表添加元素,若列表为 null 或空则新建可变列表
     * @param list 源列表
     * @param obj 待添加的元素
     * @return 添加后的列表
     */
    public static <T> List<T> safeAdd(List<T> list, T obj) {
        if (list == null || list.isEmpty()) {
            List<T> newList = new ArrayList<>(1);
            newList.add(obj);
            return newList;
        }
        list.add(obj);
        return list;
    }

    /**
     * 按谓词过滤集合,返回满足条件的元素列表
     * @param list 源集合
     * @param filter 过滤谓词
     * @return 满足条件的元素列表 源集合为空时返回空列表
     */
    public static <T> List<T> filter(Collection<T> list, Predicate<T> filter) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        for (T element : list) {
            if (filter.test(element)) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * 在列表中查找恰好唯一一个满足过滤条件的元素
     * @return 若满足条件的元素不是恰好一个 (零个或多个)则返回 null
     */

    public static <T> T filterOnlyOne(List<T> list, Predicate<T> filter) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        T found = null;
        for (T element : list) {
            if (filter.test(element)) {
                if (found != null) {
                    // 找到了第二个
                    return null;
                }
                found = element;
            }
        }
        return found;
    }

    /**
     * 判断集合中所有元素是否都满足指定条件
     * @param list 源集合
     * @param test 判断谓词
     * @return 集合非空且所有元素均满足条件时返回 true
     */
    public static <T> boolean allMatch(Collection<T> list, Predicate<T> test) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (T element : list) {
            if (!test.test(element)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断集合中是否没有任何元素满足指定条件
     * @param list 源集合
     * @param test 判断谓词
     * @return 无元素满足条件时返回 true
     */
    public static <T> boolean noneMatch(Collection<T> list, Predicate<T> test) {
        return !anyMatch(list, test);
    }

    /**
     * 判断集合中是否存在至少一个元素满足指定条件
     * @param list 源集合
     * @param test 判断谓词
     * @return 存在满足条件的元素时返回 true
     */
    public static <T> boolean anyMatch(Collection<T> list, Predicate<T> test) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (T element : list) {
            if (test.test(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将枚举 ({@link Enumeration})转换为列表
     * @param enumeration 源枚举
     * @return 转换后的列表 枚举为 null 或空时返回空列表
     */
    public static <T> List<T> enumerationToList(Enumeration<T> enumeration) {
        if (enumeration == null || enumeration == Collections.emptyEnumeration()) {
            return Collections.emptyList();
        }
        List<T> list = new ArrayList<>();
        while (enumeration.hasMoreElements()) {
            list.add(enumeration.nextElement());
        }
        return list;
    }

}
