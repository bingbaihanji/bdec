package com.bingbaihanji.bdec.decompiler.structuring;

import com.bingbaihanji.bdec.decompiler.cfg.BasicBlock;

import java.util.Set;

public record LoopInfo(
        BasicBlock header,
        Set<BasicBlock> latches,
        Set<BasicBlock> body,
        Set<BasicBlock> exits
) {
}
