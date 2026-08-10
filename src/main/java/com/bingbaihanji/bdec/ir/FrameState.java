package com.bingbaihanji.bdec.ir;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * 栈帧状态记录.
 * <p>
 * 表示字节码栈帧模拟时某个执行点的完整状态,包含操作数栈和局部变量表.
 * 每个模拟步骤都以此记录为输入,模拟执行一条指令后产生新的帧状态.
 * </p>
 *
 * @param stack  操作数栈(双向队列模拟,栈顶对应队尾)
 * @param locals 局部变量数组,按槽位索引存储
 */
public record FrameState(Deque<Value> stack, Value[] locals) {

    /**
     * 创建一个空的帧状态.
     *
     * @return 空栈,零长度局部变量表的帧状态
     */
    public static FrameState empty() {
        return new FrameState(new ArrayDeque<>(), new Value[0]);
    }

    /**
     * 创建一个具有指定数量局部变量槽位的帧状态.
     *
     * @param count 局部变量槽位数
     * @return 空栈,指定槽位数的帧状态
     */
    public static FrameState withLocals(int count) {
        Value[] l = new Value[count];
        return new FrameState(new ArrayDeque<>(), l);
    }

    /**
     * 深拷贝当前帧状态.
     * 栈和局部变量表都会复制一份独立的副本.
     *
     * @return 独立拷贝的帧状态
     */
    public FrameState copy() {
        Deque<Value> newStack = new ArrayDeque<>(stack);
        Value[] newLocals = Arrays.copyOf(locals, locals.length);
        return new FrameState(newStack, newLocals);
    }
}
