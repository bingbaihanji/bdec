package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 通用三元组 (Triple)
 * <p>
 * 不可变记录 (record),提供了常用的结构替换,映射,结合 Pair 交互以及转换方法 
 *
 * @param first  第一个元素
 * @param second 第二个元素
 * @param third  第三个元素
 * @param <A>    第一个元素的类型
 * @param <B>    第二个元素的类型
 * @param <C>    第三个元素的类型
 */
public record Triple<A, B, C>(A first, B second, C third) {

    /**
     * 静态工厂方法,用于创建三元组
     */
    public static <A, B, C> Triple<A, B, C> of(A first, B second, C third) {
        return new Triple<>(first, second, third);
    }

    // 链式替换 API

    /**
     * 当三个元素类型相同时的特化方法,保留类型信息
     */
    public static <T> List<T> toListSameType(Triple<T, T, T> triple) {
        Objects.requireNonNull(triple, "triple 不能为空");
        return Collections.unmodifiableList(Arrays.asList(triple.first(), triple.second(), triple.third()));
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

    public Triple<A, B, C> withFirst(A first) {
        return new Triple<>(first, this.second, this.third);
    }

    // 映射变换 API

    public Triple<A, B, C> withSecond(B second) {
        return new Triple<>(this.first, second, this.third);
    }

    public Triple<A, B, C> withThird(C third) {
        return new Triple<>(this.first, this.second, third);
    }

    public <A2> Triple<A2, B, C> mapFirst(Function<? super A, ? extends A2> mapper) {
        Objects.requireNonNull(mapper, "first mapper 不能为空");
        return new Triple<>(mapper.apply(first), second, third);
    }

    public <B2> Triple<A, B2, C> mapSecond(Function<? super B, ? extends B2> mapper) {
        Objects.requireNonNull(mapper, "second mapper 不能为空");
        return new Triple<>(first, mapper.apply(second), third);
    }

    // 与 Pair 交互 API

    public <C2> Triple<A, B, C2> mapThird(Function<? super C, ? extends C2> mapper) {
        Objects.requireNonNull(mapper, "third mapper 不能为空");
        return new Triple<>(first, second, mapper.apply(third));
    }

    public <A2, B2, C2> Triple<A2, B2, C2> mapAll(Function<? super A, ? extends A2> firstMapper,
                                                  Function<? super B, ? extends B2> secondMapper,
                                                  Function<? super C, ? extends C2> thirdMapper) {
        Objects.requireNonNull(firstMapper, "first mapper 不能为空");
        Objects.requireNonNull(secondMapper, "second mapper 不能为空");
        Objects.requireNonNull(thirdMapper, "third mapper 不能为空");
        return new Triple<>(firstMapper.apply(first), secondMapper.apply(second), thirdMapper.apply(third));
    }

    // 集合与流转换

    /**
     * 截取前两个元素,生成 Pair<A, B>
     */
    public Pair<A, B> toPairLeft() {
        return Pair.of(first, second);
    }

    /**
     * 截取后两个元素,生成 Pair<B, C>
     */
    public Pair<B, C> toPairRight() {
        return Pair.of(second, third);
    }

    /**
     * 转换为包含三个元素的不可变列表 (安全支持 null 元素)
     */
    public List<Object> toList() {
        return Collections.unmodifiableList(Arrays.asList(first, second, third));
    }

    // 函数式交互 API

    public Stream<Object> stream() {
        return Stream.of(first, second, third);
    }

    /**
     * 对三元组应用三元函数并返回结果
     */
    public <R> R apply(TripleFunction<? super A, ? super B, ? super C, ? extends R> function) {
        Objects.requireNonNull(function, "function 不能为空");
        return function.apply(first, second, third);
    }

    // 优化的 toString 实现

    /**
     * 对三元组应用无返回值的消费函数
     */
    public void accept(TriConsumer<? super A, ? super B, ? super C> action) {
        Objects.requireNonNull(action, "action 不能为空");
        action.accept(first, second, third);
    }

    @Override
    public String toString() {
        return "Triple[" + "first=" + valueToString(first) + ", second=" + valueToString(second) + ", third="
                + valueToString(third) + ']';
    }

    // 函数式接口定义

    /**
     * 三元函数接口,用于 {@link Triple#apply(TripleFunction)}
     */
    @FunctionalInterface
    public interface TripleFunction<A, B, C, R> {

        R apply(A a, B b, C c);
    }

    /**
     * 三元消费接口,用于 {@link Triple#accept(TriConsumer)}
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {

        void accept(A a, B b, C c);
    }
}