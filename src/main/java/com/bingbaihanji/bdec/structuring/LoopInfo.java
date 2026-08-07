package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Set;

public record LoopInfo(
        BasicBlock header,
        Set<BasicBlock> latches,
        Set<BasicBlock> body,
        Set<BasicBlock> exits
) {}
