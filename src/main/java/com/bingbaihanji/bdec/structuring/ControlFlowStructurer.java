package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

        // 2. Analyze switch and try-catch (detection only, no folding yet)
        List<SwitchInfo> switchInfos = switchAnalyzer.analyze(graph, dom);
        List<TryCatchInfo> tryCatchInfos = tryCatchAnalyzer.analyze(graph);

        // 3. Build annotation maps
        Map<BasicBlock, LoopInfo> loopAnns = new HashMap<>();
        Map<BasicBlock, IfInfo> ifAnns = new HashMap<>();
        Map<BasicBlock, SwitchInfo> switchAnns = new HashMap<>();
        Map<BasicBlock, TryCatchInfo> tryCatchAnns = new HashMap<>();

        // Record switch headers
        for (SwitchInfo si : switchInfos) {
            switchAnns.put(si.header(), si);
        }
        // Record try-catch headers (handler entry is the key)
        for (TryCatchInfo tci : tryCatchInfos) {
            tryCatchAnns.put(tci.handlerBlock(), tci);
        }

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
                    loopAnns.put(loop.header(), loop);
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
                    ifAnns.put(ifInfo.header(), ifInfo);
                    graph = foldIf(graph, ifInfo);
                    changed = true;
                }
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                continue;
            }

            // 4c. Sequences
            ControlFlowGraph prevGraph = graph;
            graph = foldSequences(graph);
            if (graph != prevGraph) {
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                changed = true;
            }
        }

        // 5. Irreducible fallback
        if (graph.blockCount() > 3) {
            graph = irreducibleHandler.handle(graph);
        }

        // 6. Generate AST with structure annotations
        BlockStatement body = blockReducer.reduce(graph, ir, loopAnns, ifAnns);
        return new StructuredMethod(ir.method(), ir, body, loopAnns, ifAnns);
    }

    // ── Folding operations ────────────────────────────────────────

    private ControlFlowGraph foldLoop(ControlFlowGraph graph, LoopInfo loop,
                                      PostDominatorTree postDom) {
        BasicBlock virtualBlock = new BasicBlock(graph.blockCount() + 1000,
                flattenInstructions(loop.body(), graph));
        return buildFoldedGraph(graph, loop.body(), virtualBlock, loop.header());
    }

    private ControlFlowGraph foldIf(ControlFlowGraph graph, IfInfo info) {
        Set<BasicBlock> allFolded = new HashSet<>();
        allFolded.addAll(info.thenBlocks());
        allFolded.addAll(info.elseBlocks());
        BasicBlock virtualBlock = new BasicBlock(graph.blockCount() + 1000,
                flattenInstructions(allFolded, graph));
        return buildFoldedGraph(graph, allFolded, virtualBlock, info.header());
    }

    private ControlFlowGraph foldSequences(ControlFlowGraph graph) {
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
                boolean onlyFallthrough = graph.outgoingOf(b1).stream()
                        .allMatch(e -> e.kind() == EdgeKind.FALL_THROUGH);
                if (onlyFallthrough && graph.predecessorsOf(b2).size() == 1) {
                    List<Instruction> merged = new ArrayList<>();
                    merged.addAll(b1.instructions());
                    merged.addAll(b2.instructions());
                    BasicBlock mergedBlock = new BasicBlock(b1.id(), merged);
                    return buildFoldedGraph(graph, Set.of(b1, b2), mergedBlock, b1);
                }
            }
        }
        return graph;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private List<Instruction> flattenInstructions(Set<BasicBlock> blocks, ControlFlowGraph graph) {
        List<Instruction> result = new ArrayList<>();
        for (BasicBlock b : graph.blocks()) {
            if (blocks.contains(b)) {
                result.addAll(b.instructions());
            }
        }
        return result;
    }

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

        List<ControlFlowEdge> newEdges = new ArrayList<>();
        Set<BasicBlock> externalBlocks = new HashSet<>(newBlocks);
        externalBlocks.remove(replacement);

        for (BasicBlock foldedBlock : folded) {
            for (ControlFlowEdge e : old.incomingOf(foldedBlock)) {
                if (!folded.contains(e.source()) && externalBlocks.contains(e.source())) {
                    newEdges.add(new ControlFlowEdge(e.source(), replacement,
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
        }
        for (BasicBlock foldedBlock : folded) {
            for (ControlFlowEdge e : old.outgoingOf(foldedBlock)) {
                if (!folded.contains(e.target()) && externalBlocks.contains(e.target())) {
                    newEdges.add(new ControlFlowEdge(replacement, e.target(),
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
        }
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
