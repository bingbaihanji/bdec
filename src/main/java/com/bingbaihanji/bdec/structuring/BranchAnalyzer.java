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
 * Detects if-then / if-else structures using the post-dominator tree.
 *
 * For a conditional header H with two successors S1, S2:
 *   Follow F = immediatePostDominator(H)
 *   If-Then:  S1 == F or S2 == F
 *   If-Else:  both != F, both eventually reach F
 */
public final class BranchAnalyzer {

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

            // Only process if this is a conditional branch (heuristic: has true/false edges)
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
                Set<BasicBlock> thenBlocks = collectBranch(s1, follow, graph);
                results.add(new IfInfo(block, follow, thenBlocks, Set.of()));
            } else if (s1 == follow) {
                Set<BasicBlock> thenBlocks = collectBranch(s2, follow, graph);
                results.add(new IfInfo(block, follow, thenBlocks, Set.of()));
            } else {
                Set<BasicBlock> thenBlocks = collectBranch(s1, follow, graph);
                Set<BasicBlock> elseBlocks = collectBranch(s2, follow, graph);
                results.add(new IfInfo(block, follow, thenBlocks, elseBlocks));
            }
        }
        return results;
    }

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
            // Only follow non-exception edges — handler blocks must not be
            // included in if/else branch bodies.
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
