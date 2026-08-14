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
                if (trueTarget == null && s != falseTarget) {
                    trueTarget = s;
                }
                if (falseTarget == null && s != trueTarget) {
                    falseTarget = s;
                }
            }
            if (trueTarget == null || falseTarget == null) {
                continue;
            }

            BasicBlock follow = postDomTree.immediatePostDominator(block);
            if (follow == null) {
                continue;
            }

            // 终止分支检测:一个分支的所有路径直达 exit 且目标块无外部前驱
            //(即该分支是 throw/return 的终止分支),此时 follow 应为
            // 另一分支的目标(延续点).
            // 例:assert 模式 if (x > 0) {} else throw——FALSE 分支直接
            // athrow 到 exit,TRUE 目标是方法的后续代码(有外部前驱).
            if (falseTarget != null
                    && isTerminalBranch(falseTarget, trueTarget, block, graph)
                    && hasExternalPred(trueTarget, block, graph)) {
                follow = trueTarget;
            } else if (trueTarget != null
                    && isTerminalBranch(trueTarget, falseTarget, block, graph)
                    && hasExternalPred(falseTarget, block, graph)) {
                follow = falseTarget;
            }

            Set<BasicBlock> thenBlocks, elseBlocks;
            boolean negateCondition = false;

            // 特判:一个分支在另一个分支的目标处汇入(一个分支是"跳过"分支).
            // 例:assert 的 $assertionsDisabled 检查——TRUE 直接落到后续代码,
            // FALSE 是 assert 体,最终也汇入后续代码.
            // 此时被汇入分支是空的"跳过",if 应取反后只包含另一分支,
            // 后续代码不属于 if.
            boolean falseJoinsTrue = branchJoins(falseTarget, trueTarget, follow, graph);
            boolean trueJoinsFalse = branchJoins(trueTarget, falseTarget, follow, graph);
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
            } else if (falseJoinsTrue) {
                // false 分支汇入 true 目标:then = false 分支(条件取反),
                // 收敛点 = true 目标,后续代码不属于 if.
                thenBlocks = collectBranch(falseTarget, trueTarget, graph);
                elseBlocks = Set.of();
                negateCondition = true;
                follow = trueTarget;
            } else if (trueJoinsFalse) {
                // true 分支汇入 false 目标:then = true 分支(条件不变),
                // 收敛点 = false 目标.
                thenBlocks = collectBranch(trueTarget, falseTarget, graph);
                elseBlocks = Set.of();
                follow = falseTarget;
            } else {
                // 两个后继都不直达 follow → if-else
                thenBlocks = collectBranch(trueTarget, follow, graph);
                elseBlocks = collectBranch(falseTarget, follow, graph);
            }

            results.add(new IfInfo(block, follow, thenBlocks, elseBlocks, negateCondition));
        }
        return results;
    }

    /** 检查目标块是否有 header 之外的前驱(即它是共享延续点) */
    private boolean hasExternalPred(BasicBlock target, BasicBlock header,
                                    ControlFlowGraph graph) {
        if (target == null) {
            return false;
        }
        for (var in : graph.incomingOf(target)) {
            if (in.source() != header) {
                return true;
            }
        }
        return false;
    }

    /** 检查分支是否为终止分支:目标块无外部前驱(仅从 header 进入),
     *  且所有路径都直达 exit,不汇入另一个分支的目标. */
    private boolean isTerminalBranch(BasicBlock start, BasicBlock otherTarget,
                                     BasicBlock header, ControlFlowGraph graph) {
        if (start == otherTarget || start == graph.exitBlock()) {
            return false;
        }
        // 目标块有 header 之外的前驱 → 它是共享延续点,不是终止分支
        for (var in : graph.incomingOf(start)) {
            if (in.source() != header) {
                return false;
            }
        }
        // 所有非异常路径都必须直达 exit
        Set<BasicBlock> visited = new java.util.HashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock curr = queue.poll();
            if (!visited.add(curr)) {
                continue;
            }
            if (curr == otherTarget) {
                return false; // 汇入了另一分支
            }
            for (var e : graph.outgoingOf(curr)) {
                if (e.kind() == EdgeKind.EXCEPTION) {
                    continue;
                }
                BasicBlock t = e.target();
                if (t == graph.exitBlock()) {
                    continue; // 正常终止
                }
                if (t == otherTarget) {
                    return false;
                }
                queue.add(t);
            }
        }
        return true;
    }

    /**
     * 检查分支区域(从 start 到 stop 的可达块)中是否存在指向 joinTarget 的边,
     * 即该分支最终汇入另一个分支的目标块.
     */
    private boolean branchJoins(BasicBlock start, BasicBlock joinTarget,
                                BasicBlock stop, ControlFlowGraph graph) {
        if (start == joinTarget || joinTarget == null || start == stop) {
            return false;
        }
        Set<BasicBlock> region = collectBranch(start, stop, graph);
        for (BasicBlock rb : region) {
            for (var e : graph.outgoingOf(rb)) {
                if (e.kind() != EdgeKind.EXCEPTION && e.target() == joinTarget) {
                    return true;
                }
            }
        }
        return false;
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
