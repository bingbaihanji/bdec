package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.ExceptionRange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects try-catch regions from the CFG's exception range metadata
 * and the method's exception handler table.
 */
public final class TryCatchAnalyzer {

    /**
     * Analyze the graph and return a list of detected try-catch structures.
     * Extracts handler regions and groups try blocks.
     */
    public List<TryCatchInfo> analyze(ControlFlowGraph graph) {
        List<ExceptionRange> ranges = graph.exceptionRanges();
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }

        List<TryCatchInfo> results = new ArrayList<>();

        for (ExceptionRange range : ranges) {
            // Find all blocks within the try range [startPc, endPc)
            Set<BasicBlock> tryBlocks = findBlocksInRange(graph, range.startPc(), range.endPc());

            // The handler block
            BasicBlock handler = range.handlerBlock();

            if (handler == null || tryBlocks.isEmpty()) {
                continue;
            }

            // Skip self-referencing exception ranges where the handler is inside
            // its own try range. These are JVM artifacts of the synchronized block's
            // monitorexit retry mechanism (e.g., try [17,20) → handler 17).
            // Without this filter, the self-looping exception edge is detected as
            // a while(true) loop by LoopAnalyzer, corrupting the CFG structure.
            if (tryBlocks.contains(handler)) {
                continue;
            }

            results.add(new TryCatchInfo(
                    tryBlocks,
                    handler,
                    range.catchType() != null ? range.catchType() : "java/lang/Throwable",
                    range.startPc(),
                    range.endPc()
            ));
        }

        return results;
    }

    /**
     * Find all basic blocks that overlap with [startPc, endPc).
     * A block may start before the try range but still contain instructions
     * within it — those blocks must also be included for exception edges.
     */
    private Set<BasicBlock> findBlocksInRange(ControlFlowGraph graph, int startPc, int endPc) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        List<BasicBlock> orderedBlocks = graph.blocks().stream()
                .filter(b -> b != graph.entryBlock() && b != graph.exitBlock())
                .sorted(java.util.Comparator.comparingInt(BasicBlock::startOffset))
                .toList();
        for (int i = 0; i < orderedBlocks.size(); i++) {
            BasicBlock b = orderedBlocks.get(i);
            int blockStart = b.startOffset();
            // Compute block end: start of next block, or endPc as fallback
            int blockEnd = (i + 1 < orderedBlocks.size())
                    ? orderedBlocks.get(i + 1).startOffset()
                    : Integer.MAX_VALUE;
            // Overlap: [blockStart, blockEnd) intersects [startPc, endPc)
            if (blockStart < endPc && blockEnd > startPc) {
                result.add(b);
            }
        }
        return result;
    }
}
