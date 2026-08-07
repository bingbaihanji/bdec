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
 * Detects natural loops in a CFG via dominator-tree back-edge analysis.
 *
 * Back-edge: N → H where H dominates N.
 * H = loop header, N = loop latch.
 */
public final class LoopAnalyzer {

    /** Sort innermost first (smallest body first). */
    public static List<LoopInfo> sortInnermostFirst(List<LoopInfo> loops) {
        List<LoopInfo> sorted = new ArrayList<>(loops);
        sorted.sort(Comparator.comparingInt(l -> l.body().size()));
        return sorted;
    }

    public List<LoopInfo> analyze(ControlFlowGraph graph, DominatorTree domTree) {
        List<LoopInfo> loops = new ArrayList<>();

        for (BasicBlock block : graph.blocks()) {
            if (block == graph.entryBlock() || block == graph.exitBlock()) {
                continue;
            }
            for (BasicBlock succ : graph.successorsOf(block)) {
                if (domTree.dominates(succ, block)) {
                    // Found back-edge: block(N) → succ(H)
                    LoopInfo loop = extractNaturalLoop(succ, block, graph, domTree);
                    loops.add(loop);
                }
            }
        }
        return removeOuterLoops(loops);
    }

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

    /** Keep only innermost loops (remove outer loops that contain inner loops). */
    private List<LoopInfo> removeOuterLoops(List<LoopInfo> loops) {
        List<LoopInfo> result = new ArrayList<>();
        for (LoopInfo loop : loops) {
            boolean isInner = true;
            for (LoopInfo other : loops) {
                if (other != loop && other.body().containsAll(loop.body())
                        && !loop.body().containsAll(other.body())) {
                    isInner = false; // other contains loop completely
                    break;
                }
            }
            if (isInner) {
                result.add(loop);
            }
        }
        return result;
    }
}
