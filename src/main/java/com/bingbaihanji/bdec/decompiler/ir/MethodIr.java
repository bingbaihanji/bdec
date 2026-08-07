package com.bingbaihanji.bdec.decompiler.ir;

import com.bingbaihanji.bdec.decompiler.bytecode.MethodModel;
import com.bingbaihanji.bdec.decompiler.cfg.ControlFlowGraph;

import java.util.List;

public interface MethodIr {

    MethodModel method();

    ControlFlowGraph controlFlowGraph();

    List<IrInstruction> instructions();

    List<Variable> variables();
}
