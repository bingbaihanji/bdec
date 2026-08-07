package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

// 对偶数据结构 不可变类型
public record Pair<L, R>(L left, R right) implements Map.Entry<L, R> {

    /**
     * 静态工厂方法,用于创建对偶组
     * @param left 第一个元素
     * @param right 第二个元素
     * @param <L> 第一个元素的类型
     * @param <R> 第二个元素的类型
     * @return 包含给定元素的对偶组
     */
    public static <L, R> Pair<L, R> of(L left, R right) {
        return new Pair<>(left, right);
    }

    /**
     * 从 Map.Entry 快速构建 Pair
     */
    public static <L, R> Pair<L, R> fromEntry(Map.Entry<L, R> entry) {
        Objects.requireNonNull(entry, "entry 不能为空");
        return new Pair<>(entry.getKey(), entry.getValue());
    }

    /**
     * 从 Map  快速构建 Pair 列表
     */
    public static <L, R> List<Pair<L, R>> fromEntry(Map<L, R> map) {
        Set<Map.Entry<L, R>> mapEntry = map.entrySet();
        List<Pair<L, R>> list = new ArrayList<>();
        for (Map.Entry<L, R> mapValue : mapEntry) {
            Pair<L, R> pair = new Pair<>(mapValue.getKey(), mapValue.getValue());
            list.add(pair);
        }
        return list;
    }

    private static String valueToString(Object value) {
        if (value == null) {
            return "null";
        }

        Class<?> clazz = value.getClass();

        if (clazz.isArray()) {
            if (clazz == int[].class) {
                return Arrays.toString((int[]) value);
            }
            if (clazz == long[].class) {
                return Arrays.toString((long[]) value);
            }
            if (clazz == double[].class) {
                return Arrays.toString((double[]) value);
            }
            if (clazz == float[].class) {
                return Arrays.toString((float[]) value);
            }
            if (clazz == boolean[].class) {
                return Arrays.toString((boolean[]) value);
            }
            if (clazz == byte[].class) {
                return Arrays.toString((byte[]) value);
            }
            if (clazz == short[].class) {
                return Arrays.toString((short[]) value);
            }
            if (clazz == char[].class) {
                return Arrays.toString((char[]) value);
            }

            return Arrays.deepToString((Object[]) value);
        }

        return String.valueOf(value);
    }

    @Override
    public L getKey() {
        return left;
    }

    @Override
    public R getValue() {
        return right;
    }

    @Override
    public R setValue(R value) {
        throw new UnsupportedOperationException("Pair 是不可变的");
    }

    /**
     * 返回一个新对偶组,仅替换第一个元素
     * @param left 新的第一个元素
     * @return 新对偶组
     */
    public Pair<L, R> withFirst(L left) {
        return new Pair<>(left, this.right);
    }

    /**
     * 返回一个新对偶组,仅替换第二个元素
     * @param right 新的第二个元素
     * @return 新对偶组
     */
    public Pair<L, R> withSecond(R right) {
        return new Pair<>(this.left, right);
    }

    /**
     * 对第一个元素应用映射函数,返回新对偶组
     * @param mapper 映射函数
     * @param <L2> 新第一个元素的类型
     * @return 映射后的对偶组
     */
    public <L2> Pair<L2, R> mapFirst(Function<? super L, ? extends L2> mapper) {
        return new Pair<>(mapper.apply(left), right);
    }

    /**
     * 对第二个元素应用映射函数,返回新对偶组
     * @param mapper 映射函数
     * @param <R2> 新第二个元素的类型
     * @return 映射后的对偶组
     */
    public <R2> Pair<L, R2> mapSecond(Function<? super R, ? extends R2> mapper) {
        return new Pair<>(left, mapper.apply(right));
    }

    /**
     * 同时对两个元素应用映射函数,返回新对偶组
     * @param leftMapper 第一个元素的映射函数
     * @param rightMapper 第二个元素的映射函数
     * @param <L2> 新第一个元素的类型
     * @param <R2> 新第二个元素的类型
     * @return 全部映射后的对偶组
     */
    public <L2, R2> Pair<L2, R2> mapAll(Function<? super L, ? extends L2> leftMapper,
                                        Function<? super R, ? extends R2> rightMapper) {
        return new Pair<>(leftMapper.apply(left), rightMapper.apply(right));
    }

    /**
     * 将对偶组转换为包含两个元素的列表 列表的顺序为 [left, right]
     * @return 包含两个元素的不可变列表
     */
    public List<Object> toList() {
        // 支持 null 元素的不可变列表
        return Collections.unmodifiableList(Arrays.asList(left, right));
    }

    /**
     * 将对偶组转换为流
     * @return 包含两个元素的流
     */
    public Stream<Object> stream() {
        return Stream.of(left, right);
    }

    /**
     * 交换两个元素的位置,返回新对偶组
     * @return 交换后的对偶组 (right, left)
     */
    public Pair<R, L> swap() {
        return new Pair<>(right, left);
    }

    /**
     * 对对偶组应用一个二元函数,返回结果
     * @param function 接收两个参数并返回结果的函数
     * @param <Results> 返回类型
     * @return 函数应用的结果
     */
    public <Results> Results apply(BiFunction<? super L, ? super R, ? extends Results> function) {
        return function.apply(left, right);
    }

    @Override
    public String toString() {
        return "Pair[" + "left=" + valueToString(left) + ", right=" + valueToString(right) + ']';
    }
}