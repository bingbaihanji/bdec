package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

public final class TryCatchAnalyzer {

    public ControlFlowGraph extract(ControlFlowGraph graph, MethodModel method) {
        // Phase 1: identify try-catch blocks from ExceptionRange list
        // Phase 2 (future): detect finally code duplication
        return graph; // pass-through for now — try-catch folding in Phase 4b
    }
}
