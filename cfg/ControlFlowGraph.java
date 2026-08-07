package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ControlFlowGraph {

    private final MethodModel method;

    private final BasicBlock entryBlock;

    private final BasicBlock exitBlock;

    private final List<BasicBlock> blocks;

    private final List<ExceptionRange> exceptionRanges;

    private final Map<BasicBlock, List<ControlFlowEdge>> outgoing;

    private final Map<BasicBlock, List<ControlFlowEdge>> incoming;

    private DominatorTree dominatorTree;

    private PostDominatorTree postDominatorTree;

    public ControlFlowGraph(MethodModel method, BasicBlock entryBlock, BasicBlock exitBlock,
                            List<BasicBlock> blocks, List<ControlFlowEdge> edges,
                            List<ExceptionRange> exceptionRanges) {
        this.method = method;
        this.entryBlock = entryBlock;
        this.exitBlock = exitBlock;
        this.blocks = List.copyOf(blocks);
        this.exceptionRanges = List.copyOf(exceptionRanges);

        this.outgoing = new HashMap<>();
        this.incoming = new HashMap<>();
        for (BasicBlock b : blocks) {
            outgoing.put(b, new ArrayList<>());
            incoming.put(b, new ArrayList<>());
        }
        outgoing.put(entryBlock, new ArrayList<>());
        incoming.put(entryBlock, new ArrayList<>());
        outgoing.put(exitBlock, new ArrayList<>());
        incoming.put(exitBlock, new ArrayList<>());

        for (ControlFlowEdge edge : edges) {
            outgoing.get(edge.source()).add(edge);
            incoming.get(edge.target()).add(edge);
        }
    }

    public MethodModel method() {return method;}

    public BasicBlock entryBlock() {return entryBlock;}

    public BasicBlock exitBlock() {return exitBlock;}

    public List<BasicBlock> blocks() {return blocks;}

    public List<ExceptionRange> exceptionRanges() {return exceptionRanges;}

    public List<ControlFlowEdge> edges() {
        List<ControlFlowEdge> all = new ArrayList<>();
        for (var list : outgoing.values()) {
            all.addAll(list);
        }
        return Collections.unmodifiableList(all);
    }

    public List<ControlFlowEdge> outgoingOf(BasicBlock block) {
        return Collections.unmodifiableList(outgoing.getOrDefault(block, List.of()));
    }

    public List<ControlFlowEdge> incomingOf(BasicBlock block) {
        return Collections.unmodifiableList(incoming.getOrDefault(block, List.of()));
    }

    public List<BasicBlock> successorsOf(BasicBlock block) {
        return outgoingOf(block).stream().map(ControlFlowEdge::target).toList();
    }

    public List<BasicBlock> predecessorsOf(BasicBlock block) {
        return incomingOf(block).stream().map(ControlFlowEdge::source).toList();
    }

    public DominatorTree dominatorTree() {
        if (dominatorTree == null) {
            dominatorTree = DominatorTree.compute(this);
        }
        return dominatorTree;
    }

    public PostDominatorTree postDominatorTree() {
        if (postDominatorTree == null) {
            postDominatorTree = PostDominatorTree.compute(this);
        }
        return postDominatorTree;
    }

    public int blockCount() {return blocks.size();}
}
