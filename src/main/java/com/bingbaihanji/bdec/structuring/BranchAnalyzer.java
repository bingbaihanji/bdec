package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分支分析器,利用后支配树检测 if-then / if-else 结构.
 *
 * <p>对于具有两个后继 S1,S2 的条件头块 H,算法如下:
 * <ul>
 *   <li>计算 F = H 的直接后支配节点(immediatePostDominator)</li>
 *   <li>If-Then:S1 == F 或 S2 == F(仅一个分支,无 else)</li>
 *   <li>If-Else:两个后继均不等于 F,且两者最终都能到达 F</li>
 * </ul>
 */
public final class BranchAnalyzer {

    /**
     * 分析控制流图中的条件分支结构.
     *
     * @param graph       控制流图
     * @param domTree     支配树(保留参数,供扩展使用)
     * @param postDomTree 后支配树
     * @return 检测到的 IfInfo 列表
     */
    public List<IfInfo> analyze(ControlFlowGraph graph, DominatorTree domTree,
                                PostDominatorTree postDomTree) {
        List<IfInfo> results = new ArrayList<>();

        for (BasicBlock block : graph.blocks()) {
            if (block == graph.entryBlock() || block == graph.exitBlock()) {
                continue;
            }
            List<BasicBlock> succs = graph.successorsOf(block);
            if (succs.size() != 2) {
                continue;
            }

            // 仅处理条件分支块(启发式:具有 TRUE_BRANCH/FALSE_BRANCH 类型的边)
            boolean hasCond = graph.outgoingOf(block).stream()
                    .anyMatch(e -> e.kind() == EdgeKind.TRUE_BRANCH || e.kind() == EdgeKind.FALSE_BRANCH);
            if (!hasCond) {
                continue;
            }

            BasicBlock follow = postDomTree.immediatePostDominator(block);
            if (follow == null) {
                continue;
            }

            BasicBlock s1 = succs.get(0), s2 = succs.get(1);

            if (s2 == follow) {
                // 第二个后继直接到合并点 → s1 是 then 体(if-then,无 else)
                Set<BasicBlock> thenBlocks = collectBranch(s1, follow, graph);
                results.add(new IfInfo(block, follow, thenBlocks, Set.of()));
            } else if (s1 == follow) {
                // 第一个后继直接到合并点 → s2 是 then 体(if-then,无 else)
                Set<BasicBlock> thenBlocks = collectBranch(s2, follow, graph);
                results.add(new IfInfo(block, follow, thenBlocks, Set.of()));
            } else {
                // 两个后继都不到合并点 → if-else
                Set<BasicBlock> thenBlocks = collectBranch(s1, follow, graph);
                Set<BasicBlock> elseBlocks = collectBranch(s2, follow, graph);
                results.add(new IfInfo(block, follow, thenBlocks, elseBlocks));
            }
        }
        return results;
    }

    /**
     * 收集从起始块到结束块(不含)之间的所有可达块.
     *
     * <p>仅沿非异常边遍历——处理器块绝不能被包含在 if/else 分支体中.
     *
     * @param start 起始基本块
     * @param stop  结束基本块(不包含在结果中)
     * @param graph 控制流图
     * @return 可达块集合(保持插入顺序)
     */
    private Set<BasicBlock> collectBranch(BasicBlock start, BasicBlock stop,
                                          ControlFlowGraph graph) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock curr = queue.poll();
            if (curr == stop || !result.add(curr)) {
                continue;
            }
            // 仅沿非异常边遍历——处理器块绝不能被包含在 if/else 分支体中
            for (var edge : graph.outgoingOf(curr)) {
                if (edge.kind() == EdgeKind.EXCEPTION) {
                    continue;
                }
                BasicBlock succ = edge.target();
                if (succ != stop) {
                    queue.add(succ);
                }
            }
        }
        return result;
    }
}
