package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程安全的前缀字典树 (Concurrent Trie Map)
 * <p>
 * 适用于 API 路由匹配,前缀搜索,路径权限过滤等高频读取场景 
 * <p>
 * <b>线程安全保证</b>: 所有公开方法均为线程安全 
 * 插入和删除使用 {@link ConcurrentHashMap#compute} 保证节点级原子性,
 * 通过 {@link AtomicReference} 实现值的安全读写 
 *
 * @param <V> 值类型
 * @author 冰白寒祭
 * @since 2026-07-29
 */
public class ConcurrentTrieMap<V> {

    private final Node<V> root = new Node<>();

    private final ConcurrentHashMap.KeySetView<String, Boolean> keySet =
            ConcurrentHashMap.newKeySet();

    /**
     * 插入或更新前缀映射
     *
     * @param key   键,不能为 null
     * @param value 值,不能为 null
     * @return 旧值,若键之前不存在则返回 null
     * @throws NullPointerException 若 key 或 value 为 null
     */
    public V put(String key, V value) {
        Objects.requireNonNull(key, "key 不能为 null");
        Objects.requireNonNull(value, "value 不能为 null");
        Node<V> current = root;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            current = current.children.computeIfAbsent(ch, k -> new Node<>());
        }
        V old = current.valueRef.getAndSet(value);
        if (old == null) {
            keySet.add(key);
        }
        return old;
    }

    /**
     * 若键不存在则插入,否则返回已有值(原子操作)
     *
     * @param key   键,不能为 null
     * @param value 值,不能为 null
     * @return 当前键对应的值(已有值或新插入值)
     * @throws NullPointerException 若 key 或 value 为 null
     */
    public V putIfAbsent(String key, V value) {
        Objects.requireNonNull(key, "key 不能为 null");
        Objects.requireNonNull(value, "value 不能为 null");
        Node<V> current = root;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            current = current.children.computeIfAbsent(ch, k -> new Node<>());
        }
        V existing = current.valueRef.updateAndGet(v -> v != null ? v : value);
        if (existing == value) {
            keySet.add(key);
        }
        return existing;
    }

    /**
     * 精确查找
     *
     * @param key 键,可为 null(返回 null)
     * @return 键对应的值,若不存在则返回 null
     */
    public V get(String key) {
        if (key == null) {
            return null;
        }
        Node<V> node = searchNode(key);
        return (node != null) ? node.valueRef.get() : null;
    }

    /**
     * 判断是否包含指定键
     *
     * @param key 键,可为 null(返回 false)
     * @return 若包含返回 {@code true}
     */
    public boolean containsKey(String key) {
        return get(key) != null;
    }

    /**
     * 删除指定键
     *
     * @param key 键,可为 null(无操作返回 null)
     * @return 被删除的值,若键不存在则返回 null
     */
    public V remove(String key) {
        if (key == null) {
            return null;
        }
        // 先查找目标节点
        Node<V> current = root;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            current = current.children.get(ch);
            if (current == null) {
                return null;
            }
        }
        V old = current.valueRef.getAndSet(null);
        if (old != null) {
            keySet.remove(key);
            // 惰性清理: 从叶子向上删除无子节点的路径
            cleanupPath(key);
        }
        return old;
    }

    /**
     * 惰性清理: 从叶子向上删除没有子节点且不是单词结尾的空节点
     */
    private void cleanupPath(String key) {
        // 从倒数第二层开始向上清理
        Node<V> current = root;
        for (int i = 0; i < key.length() - 1; i++) {
            char ch = key.charAt(i);
            Node<V> next = current.children.get(ch);
            if (next == null) {
                return;
            }
            current = next;
        }
        // current 现在是倒数第二层节点,逆序清理
        for (int i = key.length() - 1; i >= 0; i--) {
            char ch = key.charAt(i);
            Node<V> child = current.children.get(ch);
            if (child != null && child.valueRef.get() == null && child.children.isEmpty()) {
                current.children.remove(ch);
            } else {
                break; // 遇到有子节点或是其他单词结尾的节点,停止清理
            }
            // 回退到上一层
            if (i == 0) {
                break;
            }
            Node<V> parent = root;
            for (int j = 0; j < i - 1; j++) {
                parent = parent.children.get(key.charAt(j));
                if (parent == null) {
                    return;
                }
            }
            current = parent;
        }
    }

    /**
     * 前缀匹配检查: 是否存在以 prefix 开头的 Key
     *
     * @param prefix 前缀,可为 null(返回 false)
     * @return 若存在返回 {@code true}
     */
    public boolean hasPrefix(String prefix) {
        if (prefix == null) {
            return false;
        }
        if (prefix.isEmpty()) {
            return !keySet.isEmpty();
        }
        return searchNode(prefix) != null;
    }

    /**
     * 最长前缀匹配 (适合路由路由/权限拦截,如 /api/user/123 -> 匹配到 /api/user/*)
     *
     * @param key 键,可为 null(返回 null)
     * @return 最长前缀匹配对应的值,若无匹配则返回 null
     */
    public V getLongestPrefixMatch(String key) {
        if (key == null) {
            return null;
        }
        Node<V> current = root;
        V longestValue = current.valueRef.get();

        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            current = current.children.get(ch);
            if (current == null) {
                break;
            }
            V value = current.valueRef.get();
            if (value != null) {
                longestValue = value;
            }
        }
        return longestValue;
    }

    /**
     * 返回当前字典树中存储的键数量
     *
     * @return 键数量
     */
    public int size() {
        return keySet.size();
    }

    /**
     * 判断字典树是否为空
     *
     * @return 若空返回 {@code true}
     */
    public boolean isEmpty() {
        return keySet.isEmpty();
    }

    /**
     * 返回所有键的快照集合
     *
     * @return 包含所有键的不可变集合
     */
    public Set<String> keySet() {
        return Set.copyOf(keySet);
    }

    /**
     * 清空字典树
     */
    public void clear() {
        root.children.clear();
        root.valueRef.set(null);
        keySet.clear();
    }

    /**
     * 沿 key 路径查找节点,找不到则返回 null
     */
    private Node<V> searchNode(String key) {
        Node<V> current = root;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            current = current.children.get(ch);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * 内部节点,使用 AtomicReference 保证 value 与 isEndOfWord 的原子性
     * valueRef 为 null 等价于 isEndOfWord=false,非 null 等价于 isEndOfWord=true
     */
    private static class Node<V> {

        final ConcurrentHashMap<Character, Node<V>> children = new ConcurrentHashMap<>();

        final AtomicReference<V> valueRef = new AtomicReference<>(null);
    }
}
