package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.ExceptionRange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * try-catch 区域分析器.
 *
 * <p>从 CFG 的异常范围元数据和方法异常处理器表中检测 try-catch 区域.
 */
public final class TryCatchAnalyzer {

    /**
     * 分析控制流图,返回检测到的 try-catch 结构列表.
     * 提取处理器区域并对 try 块进行分组.
     *
     * @param graph 控制流图
     * @return 检测到的 TryCatchInfo 列表
     */
    public List<TryCatchInfo> analyze(ControlFlowGraph graph) {
        List<ExceptionRange> ranges = graph.exceptionRanges();
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }

        List<TryCatchInfo> results = new ArrayList<>();

        for (ExceptionRange range : ranges) {
            // 查找 try 范围 [startPc, endPc) 内的所有基本块
            Set<BasicBlock> tryBlocks = findBlocksInRange(graph, range.startPc(), range.endPc());

            // 获取处理器块
            BasicBlock handler = range.handlerBlock();

            if (handler == null || tryBlocks.isEmpty()) {
                continue;
            }

            // 跳过自引用的异常范围——即处理器位于自身 try 范围内的情形.
            // 这是 synchronized 块 monitorexit 重试机制的 JVM 伪影
            //(例如 try [17,20) → handler 17).
            // 不过滤此项,LoopAnalyzer 会将自循环异常边误检测为 while(true) 循环,
            // 从而破坏 CFG 结构.
            if (tryBlocks.contains(handler)) {
                continue;
            }

            results.add(new TryCatchInfo(
                    tryBlocks,
                    handler,
                    range.catchType() != null ? range.catchType() : "java/lang/Throwable",
                    range.startPc(),
                    range.endPc()
            ));
        }

        return results;
    }

    /**
     * 查找与 [startPc, endPc) 重叠的所有基本块.
     * 某块可能起始于 try 范围之前,但仍包含范围内的指令——
     * 这些块也必须被包含以正确连接异常边.
     *
     * @param graph   控制流图
     * @param startPc try 范围起始字节码偏移
     * @param endPc   try 范围结束字节码偏移(不包含)
     * @return 与 try 范围重叠的基本块集合
     */
    private Set<BasicBlock> findBlocksInRange(ControlFlowGraph graph, int startPc, int endPc) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        List<BasicBlock> orderedBlocks = graph.blocks().stream()
                .filter(b -> b != graph.entryBlock() && b != graph.exitBlock())
                .sorted(java.util.Comparator.comparingInt(BasicBlock::startOffset))
                .toList();
        for (int i = 0; i < orderedBlocks.size(); i++) {
            BasicBlock b = orderedBlocks.get(i);
            int blockStart = b.startOffset();
            // 计算块结束偏移:下一个块的起始偏移,回退到 endPc
            int blockEnd = (i + 1 < orderedBlocks.size())
                    ? orderedBlocks.get(i + 1).startOffset()
                    : Integer.MAX_VALUE;
            // 重叠条件:[blockStart, blockEnd) 与 [startPc, endPc) 有交集
            if (blockStart < endPc && blockEnd > startPc) {
                result.add(b);
            }
        }
        return result;
    }
}
