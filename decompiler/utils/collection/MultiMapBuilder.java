package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 多值映射构建器
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @param <C> 值集合类型
 * @author xDark
 */
public final class MultiMapBuilder<K, V, C extends Collection<V>> {

    private final Supplier<? extends Map<K, Collection<V>>> mapSupplier;

    private Supplier<Collection<V>> collectionSupplier;

    private MultiMapBuilder(Supplier<? extends Map<K, Collection<V>>> mapSupplier) {
        this.mapSupplier = mapSupplier;
    }

    /**
     * 创建以哈希 Map 作为键存储的构建器
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新建的构建器
     */
    public static <K, V> MultiMapBuilder<K, V, Collection<V>> hashKeys() {
        return new MultiMapBuilder<>(HashMap::new);
    }

    /**
     * 创建以树形 Map 作为键存储的构建器
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新建的构建器
     */
    public static <K extends Comparable<K>, V> MultiMapBuilder<K, V, Collection<V>> treeKeys() {
        return new MultiMapBuilder<>(TreeMap::new);
    }

    /**
     * 创建以枚举 Map 作为键存储的构建器
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新建的构建器
     */
    public static <K extends Enum<K>, V> MultiMapBuilder<K, V, Collection<V>> enumKeys(Class<K> type) {
        return new MultiMapBuilder<>(() -> new EnumMap<>(type));
    }

    /**
     * 创建以枚举 Map 作为键存储的构建器
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新建的构建器
     */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static <K extends Enum<K>, V> MultiMapBuilder<K, V, Collection<V>> enumKeys(K... typeHint) {
        return enumKeys((Class<K>) typeHint.getClass().getComponentType());
    }

    /**
     * 创建以自定义 Map 作为键存储的构建器
     * @param supplier Map 供给函数
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新建的构建器
     */
    public static <K, V> MultiMapBuilder<K, V, Collection<V>> keys(Supplier<? extends Map<K, Collection<V>>> supplier) {
        return new MultiMapBuilder<>(supplier);
    }

    /**
     * 将底层值集合设置为 ArrayList
     * @return 当前构建器
     */
    public MultiMapBuilder<K, V, List<V>> arrayValues() {
        collectionSupplier = ArrayList::new;
        return upgrade();
    }

    /**
     * 将底层值集合设置为 HashSet
     * @return 当前构建器
     */
    public MultiMapBuilder<K, V, Set<V>> hashValues() {
        collectionSupplier = HashSet::new;
        return upgrade();
    }

    /**
     * 将底层值集合设置为 EnumSet
     * @return 当前构建器
     */
    @SafeVarargs
    @SuppressWarnings({"unchecked", "rawtypes"})
    public final MultiMapBuilder<K, V, Set<V>> enumValues(V... typeHint) {
        Class<Enum> type = (Class<Enum>) typeHint.getClass().getComponentType();
        if (!type.isEnum()) {
            throw new IllegalStateException("Values are not a enum");
        }
        collectionSupplier = () -> (Set) EnumSet.noneOf(type);
        return upgrade();
    }

    /**
     * @param collectionSupplier 值集合供给函数
     * @param <C1> 底层值集合类型
     * @return 当前构建器
     */
    @SuppressWarnings("unchecked")
    public <C1 extends Collection<V>> MultiMapBuilder<K, V, C1> values(Supplier<C1> collectionSupplier) {
        this.collectionSupplier = (Supplier<Collection<V>>) collectionSupplier;
        return upgrade();
    }

    /**
     * @return 构建完成的多值映射
     */
    @SuppressWarnings("unchecked")
    public MultiMap<K, V, C> build() {
        return MultiMap.from((Map<K, C>) mapSupplier.get(), (Supplier<C>) collectionSupplier);
    }

    @SuppressWarnings("unchecked")
    private <C1 extends Collection<V>> MultiMapBuilder<K, V, C1> upgrade() {
        return (MultiMapBuilder<K, V, C1>) this;
    }

}
