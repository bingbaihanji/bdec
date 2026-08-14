package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

/**
 * 不可归约控制流图处理器.
 *
 * <p>当 CFG 无法完全归约为结构化形式时作为回退方案.
 * 当前为透传占位实现(直接返回原图),后续可在此实现
 * 节点分裂、标记化 break/continue、goto 回退等降级策略.
 */
public final class IrreducibleHandler {

    /**
     * 处理不可归约的控制流图.
     *
     * @param graph 原始 CFG
     * @return 处理后的 CFG
     */
    public ControlFlowGraph handle(ControlFlowGraph graph) {
        return graph; // 透传占位:暂未实现不可归约降级
    }
}
