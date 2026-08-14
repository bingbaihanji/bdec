package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.bytecode.opcode.Opcode;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

            // 计算 switch 的 follow 块(直接后支配节点),
            // 作为 case 体扩展的停止边界.
            BasicBlock follow = null;
            try {
                var postDom = PostDominatorTree.compute(graph);
                follow = postDom.immediatePostDominator(block);
            } catch (Exception ignored) {
                // 后支配树计算失败则无边界限制
            }

            for (Map.Entry<Integer, Set<BasicBlock>> entry : caseBodies.entrySet()) {
                Set<BasicBlock> expanded = expandBody(entry.getValue(), allCaseHeaders,
                        graph, dom, follow);
                entry.setValue(expanded);
            }
            if (!defaultBody.isEmpty()) {
                defaultBody = expandBody(defaultBody, allCaseHeaders, graph, dom, follow);
            }

            boolean isTableSwitch = block.lastInstruction() != null
                    && Objects.requireNonNull(block.lastInstruction()).opcode() == Opcode.TABLESWITCH.code();

            results.add(new SwitchInfo(block, caseBodies, defaultBody, isTableSwitch));
        }

        return results;
    }

    /**
     * 扩展入口块集合,包含被它们支配的块,在遇到其他 case 头
     * 或 follow 块(switch 的合并点)时停止.
     *
     * @param entries         入口块集合
     * @param allCaseHeaders  所有 case 头块集合
     * @param graph           控制流图
     * @param dom             支配树
     * @param follow          switch 的 follow 块(可为 null)
     * @return 扩展后的块集合
     */
    private Set<BasicBlock> expandBody(Set<BasicBlock> entries,
                                       Set<BasicBlock> allCaseHeaders,
                                       ControlFlowGraph graph,
                                       DominatorTree dom,
                                       BasicBlock follow) {
        // 入口块也可能是 follow(如两级字符串 switch 中 hash 分派的
        // default 目标就是后续临时变量分派,即后支配节点).
        // follow 是 switch 的合并点,不属于任何 case 体——
        // 作为入口时也必须排除,否则 case 体会吞掉合并点之后的整个区域.
        Set<BasicBlock> body = new HashSet<>(entries);
        Deque<BasicBlock> queue = new ArrayDeque<>(entries);
        Set<BasicBlock> visited = new HashSet<>(entries);
        if (follow != null) {
            body.remove(follow);
            queue.remove(follow);
            visited.remove(follow);
        }

        while (!queue.isEmpty()) {
            BasicBlock current = queue.poll();
            for (BasicBlock succ : graph.successorsOf(current)) {
                if (!visited.add(succ)) {
                    continue;
                }
                // 遇到其他 case 头,follow 块或 exit 块时停止
                if (succ == graph.exitBlock()) {
                    continue;
                }
                if (succ == follow) {
                    continue; // 到达 switch 合并点,停止扩展
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
