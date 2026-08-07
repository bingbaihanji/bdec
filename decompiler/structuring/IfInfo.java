package com.bingbaihanji.bdec.decompiler.structuring;

import com.bingbaihanji.bdec.decompiler.cfg.BasicBlock;

import java.util.Set;

public record IfInfo(
        BasicBlock header,
        BasicBlock follow,
        Set<BasicBlock> thenBlocks,
        Set<BasicBlock> elseBlocks
) {
}
