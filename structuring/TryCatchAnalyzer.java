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
     * Find all basic blocks whose start offset falls within [startPc, endPc).
     */
    private Set<BasicBlock> findBlocksInRange(ControlFlowGraph graph, int startPc, int endPc) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        for (BasicBlock b : graph.blocks()) {
            if (b == graph.entryBlock() || b == graph.exitBlock()) {
                continue;
            }
            int blockStart = b.startOffset();
            if (blockStart >= startPc && blockStart < endPc) {
                result.add(b);
            }
        }
        return result;
    }
}
