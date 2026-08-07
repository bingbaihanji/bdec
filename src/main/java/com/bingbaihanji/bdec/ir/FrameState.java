package com.bingbaihanji.bdec.ir;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public record FrameState(Deque<Value> stack, Value[] locals) {

    public static FrameState empty() {
        return new FrameState(new ArrayDeque<>(), new Value[0]);
    }

    public static FrameState withLocals(int count) {
        Value[] l = new Value[count];
        return new FrameState(new ArrayDeque<>(), l);
    }

    public FrameState copy() {
        Deque<Value> newStack = new ArrayDeque<>(stack);
        Value[] newLocals = Arrays.copyOf(locals, locals.length);
        return new FrameState(newStack, newLocals);
    }
}
