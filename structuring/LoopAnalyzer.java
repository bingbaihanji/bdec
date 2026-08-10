package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 循环分析器,通过支配树回边分析检测 CFG 中的自然循环.
 *
 * <p>回边定义:边 N → H 满足 H 支配 N(H dominates N).
 * 其中 H 为循环头(loop header),N 为循环尾(loop latch).
 */
public final class LoopAnalyzer {

    /**
     * 将循环列表按最内层优先排序(循环体最小的排在前面).
     *
     * @param loops 原始循环列表
     * @return 最内层优先排序后的列表
     */
    public static List<LoopInfo> sortInnermostFirst(List<LoopInfo> loops) {
        List<LoopInfo> sorted = new ArrayList<>(loops);
        sorted.sort(Comparator.comparingInt(l -> l.body().size()));
        return sorted;
    }

    /**
     * 分析控制流图中所有自然循环.
     *
     * <p>遍历图中所有边,通过支配树检测回边.
     * 返回按最内层优先排序的循环列表,ControlFlowStructurer 将按顺序折叠
     *(内层先于外层),从而正确处理嵌套关系.
     *
     * @param graph   控制流图
     * @param domTree 支配树
     * @return 最内层优先排序的 LoopInfo 列表
     */
    public List<LoopInfo> analyze(ControlFlowGraph graph, DominatorTree domTree) {
        List<LoopInfo> loops = new ArrayList<>();

        for (BasicBlock block : graph.blocks()) {
            if (block == graph.entryBlock() || block == graph.exitBlock()) {
                continue;
            }
            for (BasicBlock succ : graph.successorsOf(block)) {
                if (domTree.dominates(succ, block)) {
                    // 发现回边:block(N) → succ(H)
                    LoopInfo loop = extractNaturalLoop(succ, block, graph, domTree);
                    loops.add(loop);
                }
            }
        }
        // 返回最内层优先排序的循环列表.ControlFlowStructurer 按此顺序折叠,
        // 确保嵌套循环的内层先于外层被处理.
        return sortInnermostFirst(loops);
    }

    /**
     * 提取由给定头块和尾块定义的自然循环.
     *
     * <p>循环体 = 头块 + 所有反向到达尾块(不含头块)的前驱节点.
     *
     * @param header  循环头块
     * @param latch   循环尾块
     * @param graph   控制流图
     * @param domTree 支配树
     * @return 提取的自然循环信息
     */
    private LoopInfo extractNaturalLoop(BasicBlock header, BasicBlock latch,
                                        ControlFlowGraph graph, DominatorTree domTree) {
        Set<BasicBlock> body = new LinkedHashSet<>();
        body.add(header);

        if (!header.equals(latch)) {
            Deque<BasicBlock> worklist = new ArrayDeque<>();
            worklist.push(latch);
            while (!worklist.isEmpty()) {
                BasicBlock curr = worklist.pop();
                if (!body.add(curr)) {
                    continue;
                }
                for (BasicBlock pred : graph.predecessorsOf(curr)) {
                    if (!body.contains(pred)) {
                        worklist.push(pred);
                    }
                }
            }
        }

        // 收集循环出口块:循环体内某块的后继不在循环体中的情况
        Set<BasicBlock> exits = new HashSet<>();
        for (BasicBlock b : body) {
            for (BasicBlock succ : graph.successorsOf(b)) {
                if (!body.contains(succ)) {
                    exits.add(b);
                }
            }
        }

        return new LoopInfo(header, Set.of(latch), body, exits);
    }
}
