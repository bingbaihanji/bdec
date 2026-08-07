package com.bingbaihanji.bdec.cfg;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DominatorTree {

    private final ControlFlowGraph cfg;

    private final Map<BasicBlock, BasicBlock> idom;

    private final Map<BasicBlock, Set<BasicBlock>> domChildren;

    private DominatorTree(ControlFlowGraph cfg, Map<BasicBlock, BasicBlock> idom) {
        this.cfg = cfg;
        this.idom = Collections.unmodifiableMap(idom);
        Map<BasicBlock, Set<BasicBlock>> children = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) {
            children.put(b, new HashSet<>());
        }
        for (var entry : idom.entrySet()) {
            BasicBlock child = entry.getKey();
            BasicBlock parent = entry.getValue();
            if (parent != null && child != cfg.entryBlock()) {
                children.get(parent).add(child);
            }
        }
        this.domChildren = Collections.unmodifiableMap(children);
    }

    public static DominatorTree computeIterative(ControlFlowGraph cfg) {
        List<BasicBlock> blocks = cfg.blocks();
        BasicBlock entry = cfg.entryBlock();
        Set<BasicBlock> allBlocks = new HashSet<>(blocks);

        Map<BasicBlock, Set<BasicBlock>> dom = new HashMap<>();
        for (BasicBlock b : blocks) {
            dom.put(b, b == entry ? Set.of(entry) : new HashSet<>(allBlocks));
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock b : blocks) {
                if (b == entry) {
                    continue;
                }
                Set<BasicBlock> newDom = new HashSet<>(allBlocks);
                List<BasicBlock> preds = cfg.predecessorsOf(b);
                if (preds.isEmpty()) {
                    newDom = new HashSet<>();
                    newDom.add(b);
                } else {
                    for (BasicBlock pred : preds) {
                        newDom.retainAll(dom.get(pred));
                    }
                    newDom.add(b);
                }
                if (!newDom.equals(dom.get(b))) {
                    dom.put(b, newDom);
                    changed = true;
                }
            }
        }

        Map<BasicBlock, BasicBlock> idom = new HashMap<>();
        for (BasicBlock b : blocks) {
            if (b == entry) {
                idom.put(b, null);
                continue;
            }
            Set<BasicBlock> strictDom = new HashSet<>(dom.get(b));
            strictDom.remove(b);

            for (BasicBlock candidate : strictDom) {
                boolean isIdom = true;
                for (BasicBlock other : strictDom) {
                    if (!other.equals(candidate) && dom.get(other).contains(candidate)) {
                        isIdom = false;
                        break;
                    }
                }
                if (isIdom) {
                    idom.put(b, candidate);
                    break;
                }
            }
        }

        return new DominatorTree(cfg, idom);
    }

    public static DominatorTree compute(ControlFlowGraph cfg) {
        return computeIterative(cfg); // TODO Phase 2b: Lengauer-Tarjan for >=200 blocks
    }

    public boolean dominates(BasicBlock a, BasicBlock b) {
        BasicBlock current = b;
        while (current != null && current != cfg.entryBlock()) {
            if (current.equals(a)) {
                return true;
            }
            current = idom.get(current);
        }
        return a == cfg.entryBlock();
    }

    public BasicBlock idom(BasicBlock block) {return idom.get(block);}

    public Set<BasicBlock> children(BasicBlock block) {
        return domChildren.getOrDefault(block, Set.of());
    }

    public Map<BasicBlock, Set<BasicBlock>> computeDominanceFrontier() {
        Map<BasicBlock, Set<BasicBlock>> df = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) {
            df.put(b, new HashSet<>());
        }

        for (BasicBlock b : cfg.blocks()) {
            List<BasicBlock> preds = cfg.predecessorsOf(b);
            if (preds.size() < 2) {
                continue;
            }
            for (BasicBlock pred : preds) {
                BasicBlock runner = pred;
                while (runner != null && !dominates(runner, b)) {
                    df.get(runner).add(b);
                    runner = idom.get(runner);
                }
            }
        }
        return df;
    }
}
