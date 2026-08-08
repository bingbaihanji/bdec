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
import java.util.Comparator;
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

    private final IrreducibleHandler irreducibleHandler = new IrreducibleHandler();

    private final FinallyRecognizer finallyRecognizer = new FinallyRecognizer();

    private BlockReducer blockReducer;

    public StructuredMethod structure(LinearIr ir, DecompileContext ctx) {
        ControlFlowGraph graph = ir.controlFlowGraph();

        // 1. Compute initial dominator trees
        DominatorTree dom = DominatorTree.compute(graph);
        PostDominatorTree postDom = PostDominatorTree.compute(graph);

        // 2. Analyze switch and try-catch (detection only, no folding yet)
        List<SwitchInfo> switchInfos = switchAnalyzer.analyze(graph, dom);
        List<TryCatchInfo> tryCatchInfos = tryCatchAnalyzer.analyze(graph);
        List<IfInfo> allIfInfos = branchAnalyzer.analyze(graph, dom, postDom);
        List<LoopInfo> allLoopInfos = loopAnalyzer.analyze(graph, dom);

        // 3. Build annotation maps
        Map<BasicBlock, LoopInfo> loopAnns = new HashMap<>();
        Map<BasicBlock, IfInfo> ifAnns = new HashMap<>();
        Map<BasicBlock, SwitchInfo> switchAnns = new HashMap<>();
        Map<BasicBlock, TryCatchInfo> tryCatchAnns = new HashMap<>();

        // Record switch headers
        for (SwitchInfo si : switchInfos) {
            switchAnns.put(si.header(), si);
        }
        // Record try-catch entries (keyed by first try block, not handler)
        for (TryCatchInfo tci : tryCatchInfos) {
            BasicBlock tryEntry = tci.tryBlocks().stream()
                    .min(Comparator.comparingInt(BasicBlock::startOffset))
                    .orElse(null);
            if (tryEntry != null) {
                tryCatchAnns.put(tryEntry, tci);
            }
        }
        // Record if/else and loop annotations from pre-fold analysis.
        // These are used directly by BlockReducer to build IfStatement/LoopStatement.
        // We do NOT fold if/else blocks in the CFG — folding destroys the structure.
        for (IfInfo ifInfo : allIfInfos) {
            ifAnns.put(ifInfo.header(), ifInfo);
        }
        for (LoopInfo loop : allLoopInfos) {
            loopAnns.put(loop.header(), loop);
        }

        // 4. Iterative folding: loops first (to simplify CFG), then sequences.
        // If/else blocks are NOT folded — BlockReducer builds them from annotations.
        boolean changed = true;
        int maxIterations = Math.max(graph.blockCount() * 2, 100);
        while (changed && maxIterations-- > 0) {
            changed = false;

            // 4a. Loops (innermost first) — fold to simplify nested CFG
            List<LoopInfo> loops = loopAnalyzer.analyze(graph, dom);
            if (!loops.isEmpty()) {
                loops = LoopAnalyzer.sortInnermostFirst(loops);
                for (LoopInfo loop : loops) {
                    BasicBlock oldHeader = loop.header();
                    int oldBlockCount = graph.blockCount();
                    graph = foldLoop(graph, loop, postDom);
                    // Migrate loop annotation to replacement virtual block
                    BasicBlock replacement = findReplacementBlock(graph, oldBlockCount);
                    loopAnns.remove(oldHeader);
                    if (replacement != null) {
                        loopAnns.put(replacement, loop);
                    }
                    changed = true;
                }
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                continue;
            }

            // 4b. Sequences — merge adjacent fallthrough blocks
            ControlFlowGraph prevGraph = graph;
            graph = foldSequences(graph);
            if (graph != prevGraph) {
                // After sequence merging, update if/else annotations:
                // if the header block was merged into a sequence, update the key
                Map<BasicBlock, IfInfo> updatedIfAnns = new HashMap<>();
                for (var entry : ifAnns.entrySet()) {
                    BasicBlock header = entry.getKey();
                    BasicBlock current = findBlockInGraph(graph, header);
                    updatedIfAnns.put(current != null ? current : header, entry.getValue());
                }
                ifAnns = updatedIfAnns;

                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                changed = true;
            }
        }

        // 5. Irreducible fallback
        if (graph.blockCount() > 3) {
            graph = irreducibleHandler.handle(graph);
        }

        // 5b. Re-analyze if/else and loop patterns on the final folded graph.
        // The pre-fold analysis results may have stale block references
        // after CFG folding modified the graph. Refresh from final state.
        DominatorTree finalDom = DominatorTree.compute(graph);
        PostDominatorTree finalPostDom = PostDominatorTree.compute(graph);
        List<IfInfo> finalIfs = branchAnalyzer.analyze(graph, finalDom, finalPostDom);
        Map<BasicBlock, IfInfo> finalIfAnns = new HashMap<>();
        for (IfInfo ifInfo : finalIfs) {
            finalIfAnns.put(ifInfo.header(), ifInfo);
        }

        // 6. Generate AST with structure annotations
        blockReducer = new BlockReducer(!ir.method().isStatic());
        BlockStatement body = blockReducer.reduce(graph, ir, loopAnns, finalIfAnns, switchAnns, tryCatchAnns);

        // 7. Post-processing: merge adjacent try-finally blocks sharing the same handler
        body = finallyRecognizer.merge(body, tryCatchAnns);

        return new StructuredMethod(ir.method(), ir, body, loopAnns, ifAnns, switchAnns, tryCatchAnns);
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

    /** Find the equivalent of an old block in the new graph after folding.
     *  Checks by ID and by instruction content match. */
    private BasicBlock findBlockInGraph(ControlFlowGraph graph, BasicBlock old) {
        for (BasicBlock b : graph.blocks()) {
            if (b.id() == old.id()) {
                return b;
            }
        }
        // If the block was merged, try to find by start offset
        for (BasicBlock b : graph.blocks()) {
            if (b.startOffset() == old.startOffset()) {
                return b;
            }
        }
        return null;
    }

    /** Find the new virtual block created by a fold operation.
     *  Virtual blocks are created with id = previousBlockCount + 1000. */
    private BasicBlock findReplacementBlock(ControlFlowGraph graph, int oldBlockCount) {
        int targetId = oldBlockCount + 1000;
        for (BasicBlock b : graph.blocks()) {
            if (b.id() == targetId) {
                return b;
            }
        }
        // Fallback: find any block not in entry/exit with instructions
        for (BasicBlock b : graph.blocks()) {
            if (b != graph.entryBlock() && b != graph.exitBlock()
                    && b.id() >= 1000 && !b.instructions().isEmpty()) {
                return b;
            }
        }
        return null;
    }

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
