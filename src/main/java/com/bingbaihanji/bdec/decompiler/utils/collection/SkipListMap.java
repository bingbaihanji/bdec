package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于跳表(Skip List)实现的有序映射(Map),键值对按照键的自然顺序({@link Comparable})排列
 *
 * <p>
 * 跳表通过维护多层索引链表来加速查找,在期望意义上可实现 O(log n) 的插入,删除和查找操作 每个节点在插入时以 50% 的概率向上层晋升,因此期望高度约为
 * 2,整体空间复杂度为 O(n)
 * </p>
 *
 * <h3>线程安全性</h3> 该类使用 {@link ReentrantReadWriteLock} 保证线程安全:
 * <ul>
 * <li>{@code get},{@code containsKey},{@code firstKey},{@code lastKey},
 * {@code ceilingKey},{@code floorKey},{@code size},{@code keySet}, {@code subMap}
 * 等读操作支持并发执行(持有读锁)</li>
 * <li>{@code put},{@code remove},{@code clear} 等写操作互斥执行(持有写锁)</li>
 * </ul>
 *
 * <h3>性能特征</h3>
 * <ul>
 * <li>{@code put},{@code get},{@code remove}:期望 O(log n) 时间</li>
 * <li>{@code subMap}:O(k + log n),其中 k 为范围内元素个数(本实现采用遍历拷贝,实际为 O(n) 的简化版本)</li>
 * <li>{@code keySet}:O(n)(拷贝快照)</li>
 * <li>空间占用:约 2n 个引用(索引层额外开销)</li>
 * </ul>
 *
 * <p>
 * <b>注意:</b>键(Key)不能为 {@code null},值(Value)也不能为 {@code null}
 * </p>
 *
 * @param <K> 键的类型,必须实现 {@link Comparable} 接口以支持比较
 * @param <V> 值的类型
 * @author 冰白寒祭
 * @since 2026-07-24
 */
public class SkipListMap<K extends Comparable<? super K>, V> {

    /**
     * 最大层数(索引层数),固定为 32,足以容纳 2^32 个元素,且随机高度不会超出此值 节点的高度范围:[1, MAX_LEVEL]
     */
    private static final int MAX_LEVEL = 32;

    /** 头节点,不存储实际键值,其 next 数组长度为 MAX_LEVEL,作为各层的起点 */
    private final Node<K, V> head;

    /** 读写锁,用于保证线程安全 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 当前跳表的实际最高层索引(从 0 开始,即底层为 0)当无元素时,level = 0 */
    private int level;

    /** 当前映射中键值对的数量 */
    private int size;

    /**
     * 构造一个空的 SkipListMap
     */
    @SuppressWarnings("unchecked")
    public SkipListMap() {
        this.head = new Node<>(null, null, MAX_LEVEL);
        this.level = 0;
        this.size = 0;
    }

    /**
     * 将指定的键值对插入到映射中如果键已存在,则用新值替换旧值并返回旧值
     *
     * <p>
     * 此方法持有写锁,是互斥操作
     * </p>
     * @param key 键,不能为 null
     * @param value 值,不能为 null
     * @return 如果键已存在,返回被替换的旧值; 否则返回 null
     * @throws NullPointerException 如果 key 或 value 为 null
     */
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }
        lock.writeLock().lock();
        try {
            // update[i] 保存每一层上最后一个小于 key 的节点
            Node<K, V>[] update = new Node[MAX_LEVEL];
            Node<K, V> current = head;

            // 从最高层开始向下搜索,记录每层的前驱
            for (int i = level; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            // 此时 current 是第 0 层上小于 key 的最大节点,检查其下一个节点是否就是 key
            current = current.next[0];
            if (current != null && current.key.compareTo(key) == 0) {
                V old = current.value;
                current.value = value;
                return old;
            }

            // 生成新节点的随机高度(层数)
            int newHeight = randomHeight();
            // 如果新高度超出当前 level,需要将 update 中新增层的前驱置为 head
            if (newHeight > level) {
                for (int i = level + 1; i <= newHeight; i++) {
                    update[i] = head;
                }
                level = newHeight;
            }

            // 创建新节点,其高度为 newHeight + 1(因为 randomHeight 返回的索引最大为 MAX_LEVEL-1)
            Node<K, V> newNode = new Node<>(key, value, newHeight + 1);
            // 在每一层插入新节点
            for (int i = 0; i <= newHeight; i++) {
                newNode.next[i] = update[i].next[i];
                update[i].next[i] = newNode;
            }
            size++;
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ======================== 主要 API ========================

    /**
     * 返回指定键所映射的值; 如果此映射不包含该键,则返回 {@code null}
     *
     * <p>
     * 此方法持有读锁,支持并发读
     * </p>
     * @param key 要查询的键
     * @return 键对应的值,若不存在则返回 null
     */
    public V get(K key) {
        if (key == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            Node<K, V> current = head;
            // 从最高层向下搜索,找到第 0 层上最接近 key 的节点
            for (int i = level; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
            }
            current = current.next[0];
            return (current != null && current.key.compareTo(key) == 0) ? current.value : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 如果存在,则从映射中删除指定键的映射关系,并返回被删除的值; 否则返回 null
     *
     * <p>
     * 此方法持有写锁,是互斥操作
     * </p>
     * @param key 要删除的键
     * @return 如果键存在,返回对应的值; 否则返回 null
     */
    public V remove(K key) {
        if (key == null) {
            return null;
        }
        lock.writeLock().lock();
        try {
            Node<K, V>[] update = new Node[MAX_LEVEL];
            Node<K, V> current = head;

            // 查找每一层上最后一个小于 key 的节点
            for (int i = level; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            // 检查第 0 层后继是否为待删除节点
            current = current.next[0];
            if (current == null || current.key.compareTo(key) != 0) {
                return null;
            }

            // 从各层链表中移除该节点
            for (int i = 0; i <= level; i++) {
                if (update[i].next[i] != current) {
                    break;  // 超过该节点存在的高度后,无需继续
                }
                update[i].next[i] = current.next[i];
            }

            // 如果最高层已无节点,降低 level
            while (level > 0 && head.next[level] == null) {
                level--;
            }
            size--;
            return current.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 返回此映射中键值对的数量
     * @return 当前映射大小
     */
    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 如果此映射不包含任何键值对,则返回 true
     * @return 空则返回 true
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 清空此映射,移除所有键值对
     *
     * <p>
     * 此方法持有写锁
     * </p>
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            // 清除头节点所有层的后续引用,帮助 GC
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            level = 0;
            size = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 如果此映射包含指定键,则返回 true
     *
     * <p>
     * 此方法持有读锁
     * </p>
     * @param key 要检查的键
     * @return 包含则返回 true,否则 false
     */
    public boolean containsKey(K key) {
        // 直接调用 get 并判断返回值,get 本身已加读锁,但可重入
        return get(key) != null;
    }

    /**
     * 返回此映射中包含的键的 {@link Set} 视图 该集合是当前映射的一个快照(拷贝),不受后续修改影响
     *
     * <p>
     * 此方法持有读锁,遍历过程中映射不会被修改
     * </p>
     * @return 包含所有键的不可修改集合(实际为 {@link LinkedHashSet},保持插入顺序)
     */
    public Set<K> keySet() {
        lock.readLock().lock();
        try {
            Set<K> keys = new LinkedHashSet<>(size);
            Node<K, V> current = head.next[0];
            while (current != null) {
                keys.add(current.key);
                current = current.next[0];
            }
            return Collections.unmodifiableSet(keys);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回此映射中第一个(最小的)键; 如果映射为空,则返回 null
     *
     * <p>
     * 此方法持有读锁
     * </p>
     * @return 最小键,或 null
     */
    public K firstKey() {
        lock.readLock().lock();
        try {
            Node<K, V> n = head.next[0];
            return n != null ? n.key : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回此映射中最后一个(最大的)键; 如果映射为空,则返回 null
     *
     * <p>
     * 此方法持有读锁
     * </p>
     * @return 最大键,或 null
     */
    public K lastKey() {
        lock.readLock().lock();
        try {
            Node<K, V> node = head.next[0];
            if (node == null) {
                return null;
            }
            while (node.next[0] != null) {
                node = node.next[0];
            }
            return node.key;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回大于或等于给定键的最小键; 如果不存在这样的键,则返回 null
     *
     * <p>
     * 此方法持有读锁
     * </p>
     * @param key 参考键(不能为 null)
     * @return 符合条件的键,或 null
     */
    public K ceilingKey(K key) {
        return findNearest(key, true);
    }

    /**
     * 返回小于或等于给定键的最大键; 如果不存在这样的键,则返回 null
     *
     * <p>
     * 此方法持有读锁
     * </p>
     * @param key 参考键(不能为 null)
     * @return 符合条件的键,或 null
     */
    public K floorKey(K key) {
        return findNearest(key, false);
    }

    /**
     * 返回此映射中键范围从 {@code fromKey}(包含)到 {@code toKey}(不包含)的子映射视图
     * 返回的映射是只读的(不可修改),且是当前映射的一个快照
     *
     * <p>
     * 注意:本实现通过遍历所有节点并拷贝到 {@link TreeMap} 中完成,时间复杂度为 O(n), 其中 n
     * 为映射总大小对于大型映射,建议使用其他支持高效范围查询的跳表实现
     * </p>
     *
     * <p>
     * 此方法持有读锁
     * </p>
     * @param fromKey 范围起始键(包含),不能为 null
     * @param toKey 范围结束键(不包含),不能为 null,且必须大于等于 fromKey
     * @return 包含指定范围内键值对的不可修改有序映射
     * @throws NullPointerException 如果任一参数为 null
     */
    public SortedMap<K, V> subMap(K fromKey, K toKey) {
        if (fromKey == null || toKey == null) {
            throw new NullPointerException("fromKey or toKey is null");
        }
        lock.readLock().lock();
        try {
            TreeMap<K, V> result = new TreeMap<>();
            Node<K, V> current = head.next[0];
            while (current != null) {
                K key = current.key;
                if (key.compareTo(fromKey) >= 0 && key.compareTo(toKey) < 0) {
                    result.put(key, current.value);
                }
                current = current.next[0];
            }
            return Collections.unmodifiableSortedMap(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 生成一个随机的节点层数索引(从 0 开始) 每个节点以 50% 的概率继续向上增加一层,因此高度分布近似几何分布
     * @return 随机层数索引,范围 [0, MAX_LEVEL-1]
     */
    private int randomHeight() {
        int h = 0;
        // 连续抛出"正面"的次数,最大不超过 MAX_LEVEL-1
        while (h < MAX_LEVEL - 1 && ThreadLocalRandom.current().nextBoolean()) {
            h++;
        }
        return h;
    }

    // ======================== 内部工具方法 ========================

    /**
     * 查找最接近给定键的键,根据 ceiling 参数决定是向上(≥)还是向下(≤)查找
     * @param key 参考键(不能为 null)
     * @param ceiling true 表示查找 ceiling(≥),false 表示查找 floor(≤)
     * @return 符合条件的键,如果不存在则返回 null
     */
    private K findNearest(K key, boolean ceiling) {
        if (key == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            Node<K, V> current = head;
            // 从最高层向下搜索,定位到第 0 层上最接近 key 的节点
            for (int i = level; i >= 0; i--) {
                while (current.next[i] != null) {
                    int cmp = current.next[i].key.compareTo(key);
                    if (ceiling) {
                        // ceiling: 只要后继 < key 就继续向右移动
                        if (cmp < 0) {
                            current = current.next[i];
                        } else {
                            break;
                        }
                    } else {
                        // floor: 后继 <= key 就继续向右移动
                        if (cmp <= 0) {
                            current = current.next[i];
                        } else {
                            break;
                        }
                    }
                }
            }
            if (ceiling) {
                // ceiling 取后继(即第一个 >= key 的节点)
                Node<K, V> next = current.next[0];
                return next != null ? next.key : null;
            } else {
                // floor 取当前节点(最后一个 <= key 的节点),但需排除头节点
                return current != head ? current.key : null;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 跳表节点内部类,存储键,值和指向下一节点的引用数组 每一层都有一个向后的指针,形成多层链表
     */
    private static final class Node<K, V> {

        final K key;  // 节点键(头节点为null)

        final Node<K, V>[] next;  // 各层后继节点, next[i]=第i层的下一个节点

        V value;  // 节点值

        /**
         * 构造一个新节点
         * @param key 键(不可为 null)
         * @param value 值(不可为 null)
         * @param height 节点高度(层数),必须大于 0
         */
        @SuppressWarnings("unchecked")
        Node(K key, V value, int height) {
            this.key = key;
            this.value = value;
            this.next = new Node[height];
        }

    }

}