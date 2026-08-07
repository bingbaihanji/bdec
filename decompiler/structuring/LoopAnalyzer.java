package com.bingbaihanji.bdec.decompiler.structuring;

import com.bingbaihanji.bdec.decompiler.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.decompiler.cfg.DominatorTree;

import java.util.List;

public interface LoopAnalyzer {

    List<LoopInfo> analyze(ControlFlowGraph graph, DominatorTree dominators);
}
