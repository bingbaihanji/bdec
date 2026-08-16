package com.bingbaihanji.test.cache;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 函数记忆化(Memoization)工具类
 * <p>
 * 将给定函数的计算结果缓存起来,当使用相同参数再次调用时直接返回缓存值, 避免重复计算 缓存采用线程安全的 {@link ConcurrentHashMap} 实现
 * </p>
 * <p>
 * <b>注意:</b>
 * <ul>
 * <li>被包装的函数不应依赖外部状态或产生副作用,否则缓存行为可能导致不可预期的结果</li>
 * <li>由于底层使用 {@link ConcurrentHashMap#computeIfAbsent(Object, Function)}, 在并发环境下,同一个 key
 * 的首次访问可能因该方法的语义而被计算多次(但最终所有线程都会得到相同的结果)</li>
 * <li>键(Key)和值(Value)均不允许为 {@code null},否则会抛出 {@link NullPointerException}</li>
 * </ul>
 * </p>
 *
 * @author Amejonah
 */
public final class MemoizedFunctions {

    private MemoizedFunctions() {
        throw new AssertionError("utility class");
    }

    /**
     * 对单参数函数进行记忆化,返回一个带有线程安全缓存的包装函数
     * <p>
     * 返回的 {@link Function} 会缓存每个输入参数对应的计算结果 当再次使用相同的参数调用时, 直接从缓存中返回之前计算的值,而不会重复执行原函数
     * </p>
     * <p>
     * <b>线程安全性:</b>缓存操作使用 {@link ConcurrentHashMap},保证线程安全 但请注意,在并发首次访问相同 key
     * 时,原函数可能被执行多次(见类说明)
     * </p>
     * @param function 被包装的单参数函数,不能为 {@code null}
     * @param <Key> 参数(键)的类型
     * @param <Value> 返回值(值)的类型
     * @return 带缓存的记忆化函数
     * @throws NullPointerException 如果 {@code function} 为 {@code null},或调用时传入的键为
     * {@code null}, 或原函数返回 {@code null}
     */
    public static <Key, Value> Function<Key, Value> memoize(Function<Key, Value> function) {
        Objects.requireNonNull(function, "function");
        return new MemoizedFunction<>(function);
    }

    /**
     * 对双参数函数进行记忆化,返回一个带有线程安全缓存的包装函数
     * <p>
     * 返回的 {@link BiFunction} 会缓存每个参数组合(两个参数)对应的计算结果 当再次使用相同的参数对调用时,直接从缓存中返回之前计算的值
     * </p>
     * <p>
     * <b>缓存结构:</b>使用两层 {@link ConcurrentHashMap},外层以第一个参数为键,内层以第二个参数为键, 从而实现高效的并发访问
     * </p>
     * <p>
     * <b>线程安全性:</b>同单参数版本,但在并发首次访问相同参数组合时,原函数可能被执行多次
     * </p>
     * @param function 被包装的双参数函数,不能为 {@code null}
     * @param <KeyA> 第一个参数(外层键)的类型
     * @param <KeyB> 第二个参数(内层键)的类型
     * @param <Value> 返回值(值)的类型
     * @return 带缓存的记忆化双参数函数
     * @throws NullPointerException 如果 {@code function} 为 {@code null},或调用时传入的任一参数为
     * {@code null}, 或原函数返回 {@code null}
     */
    public static <KeyA, KeyB, Value> BiFunction<KeyA, KeyB, Value> memoize(BiFunction<KeyA, KeyB, Value> function) {
        Objects.requireNonNull(function, "function");
        return new BiMemoizedFunction<>(function);
    }

    /**
     * 单参数记忆化函数的内部实现 使用一个 {@link ConcurrentHashMap} 作为缓存
     */
    private static final class MemoizedFunction<Key, Value> implements Function<Key, Value> {

        private final Map<Key, Value> cache = new ConcurrentHashMap<>();

        private final Function<Key, Value> function;

        private MemoizedFunction(Function<Key, Value> function) {
            this.function = function;
        }

        @Override
        public Value apply(Key key) {
            return cache.computeIfAbsent(key, function);
        }

    }

    /**
     * 双参数记忆化函数的内部实现 使用两层 {@link ConcurrentHashMap} 结构缓存
     */
    private static final class BiMemoizedFunction<KeyA, KeyB, Value> implements BiFunction<KeyA, KeyB, Value> {

        private final Map<KeyA, Map<KeyB, Value>> cache = new ConcurrentHashMap<>();

        private final BiFunction<KeyA, KeyB, Value> function;

        private BiMemoizedFunction(BiFunction<KeyA, KeyB, Value> function) {
            this.function = function;
        }

        @Override
        public Value apply(KeyA keyA, KeyB keyB) {
            return cache.computeIfAbsent(keyA, _ -> new ConcurrentHashMap<>())
                    .computeIfAbsent(keyB, k -> function.apply(keyA, keyB));
        }

    }

}