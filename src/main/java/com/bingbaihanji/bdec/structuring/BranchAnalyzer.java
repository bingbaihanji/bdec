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
 *
 * <p><b>条件取反:</b>CFG 中 TRUE_BRANCH 边指向跳转目标(字节码条件为真时执行的位置),
 * FALSE_BRANCH 边指向直落路径(字节码条件为假时执行的位置).
 * CONDITION IR 指令已将 ifeq 翻译为 {@code !(value)} 等形式.
 * 当 then 体来自 FALSE_BRANCH 路径时,表示"条件为假时执行的代码",
 * 因此需要对 CONDITION 再次取反以产生正确的 Java 语义.
 */
public final class BranchAnalyzer {

    /**
     * 分析控制流图中的条件分支结构.
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

            // 通过边类型识别 true 和 false 分支
            BasicBlock trueTarget = null, falseTarget = null;
            for (var edge : graph.outgoingOf(block)) {
                if (edge.kind() == EdgeKind.TRUE_BRANCH) {
                    trueTarget = edge.target();
                } else if (edge.kind() == EdgeKind.FALSE_BRANCH) {
                    falseTarget = edge.target();
                }
            }
            if (trueTarget == null && falseTarget == null) {
                continue;
            }
            // 用剩余的后继填补缺失的目标
            for (BasicBlock s : succs) {
                if (trueTarget == null && s != falseTarget) trueTarget = s;
                if (falseTarget == null && s != trueTarget) falseTarget = s;
            }
            if (trueTarget == null || falseTarget == null) {
                continue;
            }

            BasicBlock follow = postDomTree.immediatePostDominator(block);
            if (follow == null) {
                continue;
            }

            Set<BasicBlock> thenBlocks, elseBlocks;
            boolean negateCondition = false;

            if (trueTarget == follow) {
                // 跳转目标直达 follow → false 分支(直落)是 then 体
                // CONDITION 已将 ifeq 翻译为 !(值),但 then 体在 CONDITION 为假时执行,
                // 因此需要取反:!(!(值)) = 值
                thenBlocks = collectBranch(falseTarget, follow, graph);
                elseBlocks = Set.of();
                negateCondition = true;
            } else if (falseTarget == follow) {
                // 直落路径直达 follow → true 分支(跳转)是 then 体
                // 不需要取反,因为 then 体在 CONDITION 为真时执行
                thenBlocks = collectBranch(trueTarget, follow, graph);
                elseBlocks = Set.of();
            } else {
                // 两个后继都不直达 follow → if-else
                thenBlocks = collectBranch(trueTarget, follow, graph);
                elseBlocks = collectBranch(falseTarget, follow, graph);
            }

            results.add(new IfInfo(block, follow, thenBlocks, elseBlocks, negateCondition));
        }
        return results;
    }

    /**
     * 收集从起始块到结束块(不含)之间的所有可达块.
     * 仅沿非异常边遍历——处理器块绝不能被包含在 if/else 分支体中.
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
