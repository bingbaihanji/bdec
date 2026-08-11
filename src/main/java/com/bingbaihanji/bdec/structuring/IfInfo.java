package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Set;

/**
 * if 条件分支的结构信息记录.
 *
 * @param header           条件判断所在的头块(包含 CONDITION 指令的块)
 * @param follow           合并点(两个分支汇聚的位置,即头块的直接后支配节点)
 * @param thenBlocks       then 分支中包含的基本块集合
 * @param elseBlocks       else 分支中包含的基本块集合(if-then 时为空集)
 * @param negateCondition  是否需要对 CONDITION 取反.
 *                         当 true 分支(跳转目标)直达 follow 而 then 体来自 false 分支时,
 *                         CONDITION 提取自字节码条件值(ifeq→值==0→!instanceof),
 *                         但 then 体执行的条件正相反,因此需要再次取反.
 */
public record IfInfo(
        BasicBlock header,
        BasicBlock follow,
        Set<BasicBlock> thenBlocks,
        Set<BasicBlock> elseBlocks,
        boolean negateCondition
) {
    /** 兼容旧调用方的便捷构造函数(默认 negateCondition=false) */
    public IfInfo(BasicBlock header, BasicBlock follow,
                  Set<BasicBlock> thenBlocks, Set<BasicBlock> elseBlocks) {
        this(header, follow, thenBlocks, elseBlocks, false);
    }
}
