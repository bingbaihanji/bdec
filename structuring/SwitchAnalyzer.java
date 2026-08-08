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
 * Detects switch statements by finding blocks that end in tableswitch/lookupswitch
 * and grouping their case targets into structured SwitchInfo records.
 */
public final class SwitchAnalyzer {

    /**
     * Analyze the graph and return a list of detected switch structures.
     * Does not modify the graph; folding is handled by the structurer.
     */
    public List<SwitchInfo> analyze(ControlFlowGraph graph, DominatorTree dom) {
        List<SwitchInfo> results = new ArrayList<>();

        for (BasicBlock block : graph.blocks()) {
            if (!block.endsWithSwitch()) {
                continue;
            }

            Map<Integer, Set<BasicBlock>> caseBodies = new LinkedHashMap<>();
            Set<BasicBlock> defaultBody = new HashSet<>();

            for (ControlFlowEdge edge : graph.outgoingOf(block)) {
                if (edge.kind() == EdgeKind.SWITCH_CASE) {
                    caseBodies.computeIfAbsent(edge.switchKey(), k -> new HashSet<>())
                            .add(edge.target());
                } else if (edge.kind() == EdgeKind.SWITCH_DEFAULT) {
                    defaultBody.add(edge.target());
                }
            }

            // Expand: for each case target, collect blocks dominated by it
            // that aren't part of another case
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
                    && block.lastInstruction().opcode() == 170; // TABLESWITCH

            results.add(new SwitchInfo(block, caseBodies, defaultBody, isTableSwitch));
        }

        return results;
    }

    /**
     * Expand a set of entry blocks to include blocks dominated by them,
     * stopping at other case headers.
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
                // Stop at other case headers or exit
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
