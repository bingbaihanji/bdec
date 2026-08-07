package com.bingbaihanji.bdec.decompiler.structuring;

import com.bingbaihanji.bdec.decompiler.cfg.BasicBlock;
import com.bingbaihanji.bdec.decompiler.cfg.DominatorTree;

import java.util.Optional;

public interface BranchAnalyzer {

    Optional<IfInfo> analyzeIf(BasicBlock header, DominatorTree postDominators);
}
