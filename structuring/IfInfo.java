package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Set;

/**
 * if 条件分支的结构信息记录.
 *
 * @param header     条件判断所在的头块(包含 CONDITION 指令的块)
 * @param follow     合并点(两个分支汇聚的位置,即头块的直接后支配节点)
 * @param thenBlocks then 分支中包含的基本块集合
 * @param elseBlocks else 分支中包含的基本块集合(if-then 时为空集)
 */
public record IfInfo(
        BasicBlock header,
        BasicBlock follow,
        Set<BasicBlock> thenBlocks,
        Set<BasicBlock> elseBlocks
) {}
