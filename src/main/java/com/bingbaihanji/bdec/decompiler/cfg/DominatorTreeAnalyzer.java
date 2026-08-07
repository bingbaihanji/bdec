package com.bingbaihanji.bdec.decompiler.cfg;

public interface DominatorTreeAnalyzer {

    DominatorTree compute(ControlFlowGraph graph);

    DominatorTree computePostDominators(ControlFlowGraph graph);
}
