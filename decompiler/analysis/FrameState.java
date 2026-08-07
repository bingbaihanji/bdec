package com.bingbaihanji.bdec.decompiler.analysis;

import com.bingbaihanji.bdec.decompiler.ir.Value;

import java.util.List;
import java.util.Optional;

public interface FrameState {

    List<Value> operandStack();

    Optional<Value> local(int slot);

    int maxLocals();

    int maxStack();
}
