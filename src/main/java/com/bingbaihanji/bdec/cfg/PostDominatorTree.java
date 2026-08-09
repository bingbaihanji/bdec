package com.bingbaihanji.bdec.cfg;

import java.util.ArrayList;
import java.util.List;

public final class PostDominatorTree {

    private final DominatorTree reverseDomTree;

    private PostDominatorTree(DominatorTree reverseDom) {
        this.reverseDomTree = reverseDom;
    }

    public static PostDominatorTree compute(ControlFlowGraph cfg) {
        ReverseControlFlowGraph reverse = new ReverseControlFlowGraph(cfg);
        DominatorTree rdt = DominatorTree.compute(reverse);
        return new PostDominatorTree(rdt);
    }

    public BasicBlock immediatePostDominator(BasicBlock block) {
        return reverseDomTree.idom(block);
    }

    public boolean postDominates(BasicBlock a, BasicBlock b) {
        return reverseDomTree.dominates(a, b);
    }

    private static class ReverseControlFlowGraph extends ControlFlowGraph {

        ReverseControlFlowGraph(ControlFlowGraph original) {
            super(original.method(), original.exitBlock(), original.entryBlock(),
                    original.blocks(), buildReversedEdges(original), original.exceptionRanges());
        }

        private static List<ControlFlowEdge> buildReversedEdges(ControlFlowGraph cfg) {
            List<ControlFlowEdge> reversed = new ArrayList<>();
            for (BasicBlock b : cfg.blocks()) {
                for (ControlFlowEdge e : cfg.outgoingOf(b)) {
                    // Create a NEW edge with source↔target swapped.
                    // The new Edge objects are stored in maps by the parent constructor,
                    // so outgoingOf/incomingOf return correctly reversed edges.
                    reversed.add(new ControlFlowEdge(e.target(), e.source(),
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
            return reversed;
        }
    }
}
