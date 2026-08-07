package com.bingbaihanji.bdec.decompiler.cfg;

import com.bingbaihanji.bdec.decompiler.bytecode.Instruction;

import java.util.List;

public interface BasicBlock {

    int id();

    int startOffset();

    int endOffset();

    List<Instruction> instructions();

    List<ControlFlowEdge> incomingEdges();

    List<ControlFlowEdge> outgoingEdges();
}
