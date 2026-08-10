package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * switch 语句分析器.
 *
 * <p>检测以 tableswitch/lookupswitch 结尾的基本块,并将其 case 目标
 * 分组为结构化的 SwitchInfo 记录.
 */
public final class SwitchAnalyzer {

    /**
     * 分析控制流图,返回检测到的 switch 结构列表.
     * 不修改图结构;折叠工作由 ControlFlowStructurer 处理.
     *
     * @param graph 控制流图
     * @param dom   支配树
     * @return 检测到的 SwitchInfo 列表
     */
    public List<SwitchInfo> analyze(ControlFlowGraph graph, DominatorTree dom) {
        List<SwitchInfo> results = new ArrayList<>();

        for (BasicBlock block : graph.blocks()) {
            if (!block.endsWithSwitch()) {
                continue;
            }

            Map<Integer, Set<BasicBlock>> caseBodies = new LinkedHashMap<>();
            Set<BasicBlock> defaultBody = new HashSet<>();

            // 根据出边类型收集 case 目标和 default 目标
            for (ControlFlowEdge edge : graph.outgoingOf(block)) {
                if (edge.kind() == EdgeKind.SWITCH_CASE) {
                    caseBodies.computeIfAbsent(edge.switchKey(), k -> new HashSet<>())
                            .add(edge.target());
                } else if (edge.kind() == EdgeKind.SWITCH_DEFAULT) {
                    defaultBody.add(edge.target());
                }
            }

            // 扩展:对每个 case 目标,收集被其支配且不属于其他 case 的块
            Set<BasicBlock> allCaseHeaders = new HashSet<>();
            caseBodies.values().forEach(allCaseHeaders::addAll);
            allCaseHeaders.addAll(defaultBody);

            for (Map.Entry<Integer, Set<BasicBlock>> entry : caseBodies.entrySet()) {
                Set<BasicBlock> expanded = expandBody(entry.getValue(), allCaseHeaders, graph, dom);
                entry.setValue(expanded);
            }
            if (!defaultBody.isEmpty()) {
                defaultBody = expandBody(defaultBody, allCaseHeaders, graph, dom);
            }

            boolean isTableSwitch = block.lastInstruction() != null
                    && block.lastInstruction().opcode() == 170; // TABLESWITCH 操作码

            results.add(new SwitchInfo(block, caseBodies, defaultBody, isTableSwitch));
        }

        return results;
    }

    /**
     * 扩展入口块集合,包含被它们支配的块,在遇到其他 case 头时停止.
     *
     * @param entries         入口块集合
     * @param allCaseHeaders  所有 case 头块集合
     * @param graph           控制流图
     * @param dom             支配树
     * @return 扩展后的块集合
     */
    private Set<BasicBlock> expandBody(Set<BasicBlock> entries,
                                       Set<BasicBlock> allCaseHeaders,
                                       ControlFlowGraph graph,
                                       DominatorTree dom) {
        Set<BasicBlock> body = new HashSet<>(entries);
        Deque<BasicBlock> queue = new ArrayDeque<>(entries);
        Set<BasicBlock> visited = new HashSet<>(entries);

        while (!queue.isEmpty()) {
            BasicBlock current = queue.poll();
            for (BasicBlock succ : graph.successorsOf(current)) {
                if (!visited.add(succ)) {
                    continue;
                }
                // 遇到其他 case 头或 exit 块时停止
                if (succ == graph.exitBlock()) {
                    continue;
                }
                if (allCaseHeaders.contains(succ) && !entries.contains(succ)) {
                    continue;
                }

                body.add(succ);
                queue.add(succ);
            }
        }

        return body;
    }
}
