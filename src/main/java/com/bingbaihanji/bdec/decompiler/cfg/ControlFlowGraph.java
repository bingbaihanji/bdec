package com.bingbaihanji.bdec.decompiler.cfg;

import com.bingbaihanji.bdec.decompiler.bytecode.MethodModel;

import java.util.List;
import java.util.Optional;

public interface ControlFlowGraph {

    MethodModel method();

    BasicBlock entryBlock();

    BasicBlock exitBlock();

    List<BasicBlock> blocks();

    List<ControlFlowEdge> edges();

    Optional<BasicBlock> blockContainingOffset(int bytecodeOffset);
}
