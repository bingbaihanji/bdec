package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 块分组工具——从 {@link BlockReducer} 中提取的相邻基本块聚合逻辑
 * (里程碑 Phase 3).
 *
 * <p>将支配树先序排序后的基本块按 CFG 边关系聚合为 {@link BlockGroup},
 * 并拆分混入处理器块与非处理器块的组.所有方法均为无状态静态方法.</p>
 */
final class BlockGrouper {

    private BlockGrouper() {}

    /**
     * 将相邻基本块聚合为组.
     * 相邻条件:前驱仅有一条 fallthrough 出边指向后继,
     * 且两者具有相同的异常覆盖范围——因为 try 前代码(如 lock.lock())
     * 与 try 体合并后将无法正确包装.
     */
    static List<BlockGroup> groupAdjacentBlocks(List<BasicBlock> blocks, ControlFlowGraph graph,
                                                Map<BasicBlock, LoopInfo> loopAnns) {
        List<BlockGroup> groups = new ArrayList<>();
        BlockGroup current = null;
        for (BasicBlock b : blocks) {
            if (current == null) {
                current = new BlockGroup(b);
            } else if (isAdjacent(current.last(), b, graph, loopAnns)) {
                current.add(b);
            } else {
                groups.add(current);
                current = new BlockGroup(b);
            }
        }
        if (current != null) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 检查两个基本块是否应为相邻关系(同一组).
     * 相邻块之间具有从前驱到后继的单条 fallthrough 边,
     * 且具有相同的异常覆盖范围——不同异常处理器的块不应被合并,
     * 否则 try 前代码(如 lock.lock())会与 try 体合并而无法正确包装.
     *
     * <p>同时检查循环边界:不将前导块与循环头块合并,
     * 否则循环初始化代码会被错误地包含在循环体内.
     */
    private static boolean isAdjacent(BasicBlock prev, BasicBlock next, ControlFlowGraph graph,
                                      Map<BasicBlock, LoopInfo> loopAnns) {
        List<BasicBlock> succs = graph.successorsOf(prev);
        if (succs.size() != 1 || succs.get(0) != next) {
            return false;
        }
        if (!graph.outgoingOf(prev).stream().allMatch(e -> e.kind() == EdgeKind.FALL_THROUGH)) {
            return false;
        }
        // next 必须是单前驱:多前驱块是合并点(如 switch 的 follow 块,
        // 或 case 体 goto 汇聚的块),不能并入前一个块.
        // 否则 switch 头会被吞入前一个 case 体的组中而丢失
        //(如 B10(前一个 switch 的 default 体) fallthrough 到 B11(下一个 switch 头)).
        if (graph.predecessorsOf(next).size() != 1) {
            return false;
        }
        // 尊重 try 边界:如果两个块具有不同的异常覆盖范围
        //(一个有异常边而另一个没有),则不合并它们.
        boolean prevHasException = graph.outgoingOf(prev).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        boolean nextHasException = graph.outgoingOf(next).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        if (prevHasException != nextHasException) {
            return false;
        }
        // 尊重 catch 处理器边界:处理器块(有 EXCEPTION 入边)不与
        // 后续非处理器块合并,否则 catch 体语句泄漏到方法体中重复出现.
        boolean prevIsHandler = graph.incomingOf(prev).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        boolean nextIsHandler = graph.incomingOf(next).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        if (prevIsHandler && !nextIsHandler) {
            return false;
        }
        // 尊重循环边界:如果后继块是循环头,不要将前导块(循环初始化代码)
        // 与循环体合并.检查 next 块本身及其内部所有块.
        if (loopAnns != null) {
            for (BasicBlock b : loopAnns.keySet()) {
                if (b == next || b.id() == next.id()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Split groups that contain both handler and non-handler blocks.
     * When a catch handler falls through to follow code, isAdjacent merges them.
     * Splitting ensures the handler body appears only inside the try-catch,
     * not duplicated in the method body.
     */
    static List<BlockGroup> splitMixedHandlerGroups(List<BlockGroup> groups,
                                                    Set<BasicBlock> handlerBlocks) {
        List<BlockGroup> result = new ArrayList<>();
        for (BlockGroup group : groups) {
            boolean hasHandler = false, hasNonHandler = false;
            for (BasicBlock b : group.blocks()) {
                if (handlerBlocks.contains(b)) {
                    hasHandler = true;
                } else {
                    hasNonHandler = true;
                }
            }
            if (hasHandler && hasNonHandler) {
                BlockGroup hg = null, ng = null;
                for (BasicBlock b : group.blocks()) {
                    if (handlerBlocks.contains(b)) {
                        if (hg == null) {
                            hg = new BlockGroup(b);
                        } else {
                            hg.add(b);
                        }
                    } else {
                        if (ng == null) {
                            ng = new BlockGroup(b);
                        } else {
                            ng.add(b);
                        }
                    }
                }
                if (hg != null) {
                    result.add(hg);
                }
                if (ng != null) {
                    result.add(ng);
                }
            } else {
                result.add(group);
            }
        }
        return result;
    }
}
