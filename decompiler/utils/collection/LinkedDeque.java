package com.bingbaihanji.bdec.decompiler.utils.collection;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 在 {@link Deque} 上链接的元素接口.
 */
interface Linked<T extends Linked<T>> {

    /**
     * 获取前一个元素,如果元素未链接或是双端队列的第一个元素,则返回 {@code null}.
     */
    T getPrevious();

    /** 设置前一个元素,如果没有链接则设置为 {@code null}. */
    void setPrevious(T prev);

    /**
     * 获取下一个元素,如果元素未链接或是双端队列的最后一个元素,则返回 {@code null}.
     */
    T getNext();

    /** 设置下一个元素,如果没有链接则设置为 {@code null}. */
    void setNext(T next);
}

/**
 * 基于链表实现的 {@link Deque} 接口,其中的链接指针与元素紧密集成.
 * 链接双端队列没有容量限制;它们会根据需要自动增长.
 * 该实现不是线程安全的;在缺少外部同步的情况下,不支持多线程并发访问.
 * 不允许使用 null 元素.
 * <p>
 * 大多数 {@code LinkedDeque} 操作在常数时间内完成,前提是假设
 * {@link Linked} 参数与该双端队列实例相关联.任何违反此假设的用法
 * 都将导致不确定的行为.
 *
 * @param <E> 此集合中保存的元素类型
 * @author ben.manes@gmail.com (Ben Manes)
 * @author bingbaihanji@gmail.com
 */
public final class LinkedDeque<E extends Linked<E>> extends AbstractCollection<E> implements Deque<E> {

    /**
     * 指向第一个节点的指针.
     * 不变式:(first == null && last == null) || (first.prev == null)
     */
    private E first;

    /**
     * 指向最后一个节点的指针.
     * 不变式:(first == null && last == null) || (last.next == null)
     */
    private E last;

    /**
     * 维护双端队列中的元素数量,使 size() 复杂度变为 O(1)
     */
    private int size;

    /**
     * 将元素链接到双端队列的前端,使其成为第一个元素.
     *
     * @param e 未链接的元素(必须非空)
     */
    private void linkFirst(final E e) {
        final E f = first;
        first = e;

        if (f == null) {
            last = e;
        } else {
            f.setPrevious(e);
            e.setNext(f);
        }
        size++;
    }

    /**
     * 将元素链接到双端队列的后端,使其成为最后一个元素.
     *
     * @param e 未链接的元素(必须非空)
     */
    private void linkLast(final E e) {
        final E l = last;
        last = e;

        if (l == null) {
            first = e;
        } else {
            l.setNext(e);
            e.setPrevious(l);
        }
        size++;
    }

    /** 取消链接非空的第一个元素. */
    private E unlinkFirst() {
        final E f = first;
        final E next = f.getNext();
        f.setNext(null);

        first = next;
        if (next == null) {
            last = null;
        } else {
            next.setPrevious(null);
        }
        size--;
        return f;
    }

    /** 取消链接非空的最后一个元素. */
    private E unlinkLast() {
        final E l = last;
        final E prev = l.getPrevious();
        l.setPrevious(null);

        last = prev;
        if (prev == null) {
            first = null;
        } else {
            prev.setNext(null);
        }
        size--;
        return l;
    }

    /** 取消链接非空的元素. */
    private void unlink(E e) {
        final E prev = e.getPrevious();
        final E next = e.getNext();

        if (prev == null) {
            first = next;
        } else {
            prev.setNext(next);
            e.setPrevious(null);
        }

        if (next == null) {
            last = prev;
        } else {
            next.setPrevious(prev);
            e.setNext(null);
        }
        size--;
    }

    @Override
    public boolean isEmpty() {
        return first == null;
    }

    private void checkNotEmpty() {
        if (isEmpty()) {
            throw new NoSuchElementException("Deque is empty");
        }
    }

    /**
     * 返回此双端队列中的元素数.
     * 时间复杂度:O(1)
     */
    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        E e = first;
        while (e != null) {
            E next = e.getNext();
            e.setPrevious(null);
            e.setNext(null);
            e = next;
        }
        first = last = null;
        size = 0;
    }

    @Override
    public boolean contains(Object o) {
        return (o instanceof Linked<?>) && contains((Linked<?>) o);
    }

    /**
     * 检查节点是否位于当前链表中(快速路径检查)
     */
    private boolean contains(Linked<?> e) {
        return (e.getPrevious() != null)
                || (e.getNext() != null)
                || (e == first);
    }

    /**
     * 将元素移动到双端队列的前端,使其成为第一个元素.
     *
     * @param e 已链接的元素(必须非空)
     */
    public void moveToFront(E e) {
        Objects.requireNonNull(e, "Element cannot be null");
        if (e != first) {
            unlink(e);
            linkFirst(e);
        }
    }

    /**
     * 将元素移动到双端队列的后端,使其成为最后一个元素.
     *
     * @param e 已链接的元素(必须非空)
     */
    public void moveToBack(E e) {
        Objects.requireNonNull(e, "Element cannot be null");
        if (e != last) {
            unlink(e);
            linkLast(e);
        }
    }

    @Override
    public E peek() {
        return peekFirst();
    }

    @Override
    public E peekFirst() {
        return first;
    }

    @Override
    public E peekLast() {
        return last;
    }

    @Override
    public E getFirst() {
        checkNotEmpty();
        return peekFirst();
    }

    @Override
    public E getLast() {
        checkNotEmpty();
        return peekLast();
    }

    @Override
    public E element() {
        return getFirst();
    }

    @Override
    public boolean offer(E e) {
        return offerLast(e);
    }

    @Override
    public boolean offerFirst(E e) {
        Objects.requireNonNull(e, "Element cannot be null");
        if (contains(e)) {
            return false;
        }
        linkFirst(e);
        return true;
    }

    @Override
    public boolean offerLast(E e) {
        Objects.requireNonNull(e, "Element cannot be null");
        if (contains(e)) {
            return false;
        }
        linkLast(e);
        return true;
    }

    @Override
    public boolean add(E e) {
        return offerLast(e);
    }

    @Override
    public void addFirst(E e) {
        if (!offerFirst(e)) {
            throw new IllegalArgumentException("Element already exists in the deque");
        }
    }

    @Override
    public void addLast(E e) {
        if (!offerLast(e)) {
            throw new IllegalArgumentException("Element already exists in the deque");
        }
    }

    @Override
    public E poll() {
        return pollFirst();
    }

    @Override
    public E pollFirst() {
        return isEmpty() ? null : unlinkFirst();
    }

    @Override
    public E pollLast() {
        return isEmpty() ? null : unlinkLast();
    }

    @Override
    public E remove() {
        return removeFirst();
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(Object o) {
        return (o instanceof Linked<?>) && remove((E) o);
    }

    private boolean remove(E e) {
        if (contains(e)) {
            unlink(e);
            return true;
        }
        return false;
    }

    @Override
    public E removeFirst() {
        checkNotEmpty();
        return unlinkFirst();
    }

    @Override
    public boolean removeFirstOccurrence(Object o) {
        return remove(o);
    }

    @Override
    public E removeLast() {
        checkNotEmpty();
        return unlinkLast();
    }

    @Override
    public boolean removeLastOccurrence(Object o) {
        return remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c, "Collection cannot be null");
        boolean modified = false;
        for (Object o : c) {
            modified |= remove(o);
        }
        return modified;
    }

    @Override
    public void push(E e) {
        addFirst(e);
    }

    @Override
    public E pop() {
        return removeFirst();
    }

    @Override
    public Iterator<E> iterator() {
        return new ForwardIterator();
    }

    @Override
    public Iterator<E> descendingIterator() {
        return new BackwardIterator();
    }

    /** 正向迭代器实现 */
    private final class ForwardIterator implements Iterator<E> {

        private E cursor = first;

        private E lastRet = null;

        @Override
        public boolean hasNext() {
            return cursor != null;
        }

        @Override
        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            lastRet = cursor;
            cursor = cursor.getNext();
            return lastRet;
        }

        @Override
        public void remove() {
            if (lastRet == null) {
                throw new IllegalStateException();
            }
            unlink(lastRet);
            lastRet = null;
        }
    }

    /** 反向迭代器实现 */
    private final class BackwardIterator implements Iterator<E> {

        private E cursor = last;

        private E lastRet = null;

        @Override
        public boolean hasNext() {
            return cursor != null;
        }

        @Override
        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            lastRet = cursor;
            cursor = cursor.getPrevious();
            return lastRet;
        }

        @Override
        public void remove() {
            if (lastRet == null) {
                throw new IllegalStateException();
            }
            unlink(lastRet);
            lastRet = null;
        }
    }
}