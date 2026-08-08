package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Set;

/** Describes a detected try-catch region. */
public record TryCatchInfo(
        Set<BasicBlock> tryBlocks,
        BasicBlock handlerBlock,
        String catchType,
        int startPc,
        int endPc
) {}
