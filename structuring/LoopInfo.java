package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Set;

/**
 * 自然循环的结构信息记录.
 *
 * @param header  循环头块(支配所有循环体块的基本块)
 * @param latches 循环尾块集合(具有指向 header 的回边的块)
 * @param body    循环体包含的所有基本块
 * @param exits   循环出口块集合(循环体中外接非循环体后继的块)
 */
public record LoopInfo(
        BasicBlock header,
        Set<BasicBlock> latches,
        Set<BasicBlock> body,
        Set<BasicBlock> exits
) {}
