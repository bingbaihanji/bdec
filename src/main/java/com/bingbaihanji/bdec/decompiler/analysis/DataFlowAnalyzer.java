package com.bingbaihanji.bdec.decompiler.analysis;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.decompiler.ir.MethodIr;

public interface DataFlowAnalyzer {

    MethodIr analyze(ControlFlowGraph graph, DecompileContext context);
}
