package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Control flow structurer — converts flat CFG with gotos into structured AST.
 *
 * Strategy: Immutable snapshots. Each fold pass returns a new ControlFlowGraph.
 * Dominator/post-dominator trees are recomputed after each successful fold.
 */
public class ControlFlowStructurer {

    private final LoopAnalyzer loopAnalyzer = new LoopAnalyzer();

    private final BranchAnalyzer branchAnalyzer = new BranchAnalyzer();

    private final SwitchAnalyzer switchAnalyzer = new SwitchAnalyzer();

    private final TryCatchAnalyzer tryCatchAnalyzer = new TryCatchAnalyzer();

    private final BlockReducer blockReducer = new BlockReducer();

    private final IrreducibleHandler irreducibleHandler = new IrreducibleHandler();

    public StructuredMethod structure(LinearIr ir, DecompileContext ctx) {
        ControlFlowGraph graph = ir.controlFlowGraph();

        // 1. Compute initial dominator trees
        DominatorTree dom = DominatorTree.compute(graph);
        PostDominatorTree postDom = PostDominatorTree.compute(graph);

        // 2. Try-Catch first (extracts exception handler regions)
        graph = tryCatchAnalyzer.extract(graph, ir.method());
        dom = DominatorTree.compute(graph);
        postDom = PostDominatorTree.compute(graph);

        // 3. Switch extraction
        graph = switchAnalyzer.extract(graph, dom);
        dom = DominatorTree.compute(graph);
        postDom = PostDominatorTree.compute(graph);

        // 4. Iterative folding: loops first, then conditionals, then sequences
        boolean changed = true;
        int maxIterations = Math.max(graph.blockCount() * 2, 100);
        while (changed && maxIterations-- > 0) {
            changed = false;

            // 4a. Loops (innermost first)
            List<LoopInfo> loops = loopAnalyzer.analyze(graph, dom);
            if (!loops.isEmpty()) {
                loops = LoopAnalyzer.sortInnermostFirst(loops);
                for (LoopInfo loop : loops) {
                    graph = foldLoop(graph, loop, postDom);
                    changed = true;
                }
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                continue;
            }

            // 4b. If-Else
            List<IfInfo> ifs = branchAnalyzer.analyze(graph, dom, postDom);
            if (!ifs.isEmpty()) {
                for (IfInfo ifInfo : ifs) {
                    graph = foldIf(graph, ifInfo);
                    changed = true;
                }
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                continue;
            }

            // 4c. Sequences (merge adjacent blocks with no branching)
            ControlFlowGraph prevGraph = graph;
            graph = foldSequences(graph);
            if (graph != prevGraph) {
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                changed = true;
            }
        }

        // 5. Irreducible fallback
        if (graph.blockCount() > 3) { // more than entry+exit+one block
            graph = irreducibleHandler.handle(graph);
        }

        // 6. Generate AST
        BlockStatement body = blockReducer.reduce(graph);
        return new StructuredMethod(ir.method(), ir, body);
    }

    /**
     * Fold a detected loop: extract body blocks, create a virtual "LoopBlock"
     * that replaces them in the graph.
     */
    private ControlFlowGraph foldLoop(ControlFlowGraph graph, LoopInfo loop,
                                      PostDominatorTree postDom) {
        // Determine loop type from exit analysis
        LoopStatement.LoopKind kind = classifyLoop(loop, graph);

        // Create a virtual consolidated block for the loop body
        BasicBlock virtualBlock = new BasicBlock(graph.blockCount() + 1000,
                flattenInstructions(loop.body(), graph));

        // Build new graph: exclude loop body blocks, add virtual block
        return buildFoldedGraph(graph, loop.body(), virtualBlock, loop.header());
    }

    /**
     * Fold an if-else structure similarly.
     */
    private ControlFlowGraph foldIf(ControlFlowGraph graph, IfInfo info) {
        Set<BasicBlock> allFolded = new HashSet<>();
        allFolded.addAll(info.thenBlocks());
        allFolded.addAll(info.elseBlocks());

        BasicBlock virtualBlock = new BasicBlock(graph.blockCount() + 1000,
                flattenInstructions(allFolded, graph));

        return buildFoldedGraph(graph, allFolded, virtualBlock, info.header());
    }

    /**
     * Merge adjacent blocks that have only fall-through edges into sequences.
     */
    private ControlFlowGraph foldSequences(ControlFlowGraph graph) {
        // Simplified: merge pairs of blocks where B→C has only FALL_THROUGH
        List<BasicBlock> regularBlocks = new ArrayList<>();
        for (BasicBlock b : graph.blocks()) {
            if (b != graph.entryBlock() && b != graph.exitBlock() && !b.instructions().isEmpty()) {
                regularBlocks.add(b);
            }
        }

        for (int i = 0; i < regularBlocks.size() - 1; i++) {
            BasicBlock b1 = regularBlocks.get(i);
            BasicBlock b2 = regularBlocks.get(i + 1);
            List<BasicBlock> succs = graph.successorsOf(b1);
            if (succs.size() == 1 && succs.get(0) == b2) {
                // Check if the only edge is FALL_THROUGH
                boolean onlyFallthrough = graph.outgoingOf(b1).stream()
                        .allMatch(e -> e.kind() == EdgeKind.FALL_THROUGH);
                if (onlyFallthrough && graph.predecessorsOf(b2).size() == 1) {
                    // Merge b1 and b2
                    List<com.bingbaihanji.bdec.bytecode.model.Instruction> merged = new ArrayList<>();
                    merged.addAll(b1.instructions());
                    merged.addAll(b2.instructions());
                    BasicBlock mergedBlock = new BasicBlock(b1.id(), merged);
                    Set<BasicBlock> toFold = Set.of(b1, b2);
                    return buildFoldedGraph(graph, toFold, mergedBlock, b1);
                }
            }
        }
        return graph;
    }

    // --- Helper methods ---

    private com.bingbaihanji.bdec.ast.stmt.LoopStatement.LoopKind classifyLoop(
            LoopInfo loop, ControlFlowGraph graph) {
        var LK = com.bingbaihanji.bdec.ast.stmt.LoopStatement.LoopKind.class;
        if (loop.exits().isEmpty()) {
            return com.bingbaihanji.bdec.ast.stmt.LoopStatement.LoopKind.WHILE;
        }
        if (loop.exits().size() == 1 && loop.exits().contains(loop.header())) {
            return com.bingbaihanji.bdec.ast.stmt.LoopStatement.LoopKind.WHILE;
        }
        if (loop.exits().stream().allMatch(e -> loop.latches().contains(e))) {
            return com.bingbaihanji.bdec.ast.stmt.LoopStatement.LoopKind.DO_WHILE;
        }
        return com.bingbaihanji.bdec.ast.stmt.LoopStatement.LoopKind.WHILE;
    }

    private List<com.bingbaihanji.bdec.bytecode.model.Instruction> flattenInstructions(
            Set<BasicBlock> blocks, ControlFlowGraph graph) {
        List<com.bingbaihanji.bdec.bytecode.model.Instruction> result = new ArrayList<>();
        for (BasicBlock b : graph.blocks()) {
            if (blocks.contains(b)) {
                result.addAll(b.instructions());
            }
        }
        return result;
    }

    /**
     * Build a new ControlFlowGraph where a set of blocks is replaced by one virtual block.
     * Preserves edges from external predecessors to the virtual block,
     * and edges from the virtual block to external successors.
     */
    private ControlFlowGraph buildFoldedGraph(ControlFlowGraph old,
                                              Set<BasicBlock> folded,
                                              BasicBlock replacement,
                                              BasicBlock anchor) {
        List<BasicBlock> newBlocks = new ArrayList<>();
        for (BasicBlock b : old.blocks()) {
            if (!folded.contains(b)) {
                newBlocks.add(b);
            }
        }
        // Insert replacement after anchor
        int anchorIdx = -1;
        for (int i = 0; i < newBlocks.size(); i++) {
            if (newBlocks.get(i).equals(anchor)) {
                anchorIdx = i;
                break;
            }
        }
        if (anchorIdx >= 0) {
            newBlocks.add(anchorIdx + 1, replacement);
        } else {
            newBlocks.add(replacement);
        }

        // Build edges
        List<ControlFlowEdge> newEdges = new ArrayList<>();
        Set<BasicBlock> externalBlocks = new HashSet<>(newBlocks);
        externalBlocks.remove(replacement);

        // Edges from external predecessors → replacement
        for (BasicBlock foldedBlock : folded) {
            for (ControlFlowEdge e : old.incomingOf(foldedBlock)) {
                if (!folded.contains(e.source()) && externalBlocks.contains(e.source())) {
                    newEdges.add(new ControlFlowEdge(e.source(), replacement,
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
        }
        // Edges from replacement → external successors
        for (BasicBlock foldedBlock : folded) {
            for (ControlFlowEdge e : old.outgoingOf(foldedBlock)) {
                if (!folded.contains(e.target()) && externalBlocks.contains(e.target())) {
                    newEdges.add(new ControlFlowEdge(replacement, e.target(),
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
        }
        // Preserve edges between non-folded blocks
        for (BasicBlock b : externalBlocks) {
            for (ControlFlowEdge e : old.outgoingOf(b)) {
                if (!folded.contains(e.target()) && externalBlocks.contains(e.target())) {
                    if (!newEdges.contains(e)) {
                        newEdges.add(e);
                    }
                }
            }
        }

        return new ControlFlowGraph(old.method(), old.entryBlock(), old.exitBlock(),
                newBlocks, newEdges, old.exceptionRanges());
    }
}
