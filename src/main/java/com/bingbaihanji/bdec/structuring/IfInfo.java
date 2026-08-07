package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Set;

public record IfInfo(
        BasicBlock header,
        BasicBlock follow,
        Set<BasicBlock> thenBlocks,
        Set<BasicBlock> elseBlocks
) {}
