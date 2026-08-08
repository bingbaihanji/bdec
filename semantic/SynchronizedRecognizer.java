package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.ExceptionRange;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recognizes synchronized blocks from {@code monitorenter/monitorexit}
 * IR instructions and their associated exception handlers.
 *
 * Inspired by Vineflower's {@code DomHelper.buildSynchronized()} which
 * detects the pattern after structuring, and CFR's
 * {@code SynchronizedBlocks} which does a DFS from monitorenter.
 *
 * JVM pattern for {@code synchronized(obj) { ... }}:
 * <pre>
 *   load obj
 *   dup           (optional — stores a copy for monitorexit)
 *   store tmp
 *   monitorenter  (pops obj)
 *   try {
 *     ... body ...
 *     load tmp
 *     monitorexit
 *   } catch (Throwable t) {
 *     load tmp
 *     monitorexit
 *     athrow
 *   }
 * </pre>
 */
public final class SynchronizedRecognizer {

    /**
     * Recognize synchronized blocks in the IR and annotate them.
     *
     * @return true if any synchronized blocks were recognized
     */
    public boolean recognize(LinearIr ir, ControlFlowGraph cfg) {
        boolean changed = false;
        List<IrInstruction> instructions = ir.instructions();

        // Find all MONITOR_ENTER instructions
        List<IrInstruction> monitorEnters = new ArrayList<>();
        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.MONITOR_ENTER) {
                monitorEnters.add(insn);
            }
        }

        if (monitorEnters.isEmpty()) {
            return false;
        }

        // Build block→instructions index
        Map<Integer, List<IrInstruction>> blockInsns = buildBlockIndex(instructions);

        for (IrInstruction enter : monitorEnters) {
            if (enter.operands().isEmpty()) {
                continue;
            }

            Value monitorObj = enter.operands().getFirst();

            // Find the exception handler that covers this monitor enter's block
            BasicBlock enterBlock = findBlock(cfg, enter.blockId());
            if (enterBlock == null) {
                continue;
            }

            ExceptionRange handler = findCoveringHandler(cfg, enterBlock);
            if (handler == null) {
                continue;
            }

            // Verify the handler does: MONITOR_EXIT + THROW
            BasicBlock handlerBlock = handler.handlerBlock();
            List<IrInstruction> handlerInsns = blockInsns.getOrDefault(handlerBlock.id(), List.of());
            if (!isMonitorExitThrow(handlerInsns, monitorObj)) {
                continue;
            }

            // Find monitorexit in the try body (normal exit path)
            boolean foundNormalExit = false;
            for (var entry : blockInsns.entrySet()) {
                if (entry.getKey() == handlerBlock.id()) {
                    continue;
                }
                for (IrInstruction insn : entry.getValue()) {
                    if (insn.opcode() == IrOpcode.MONITOR_EXIT && matchesObject(insn, monitorObj)) {
                        foundNormalExit = true;
                        break;
                    }
                }
                if (foundNormalExit) {
                    break;
                }
            }

            if (foundNormalExit) {
                // Mark the enter instruction block as synchronized
                enter.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.SYNCHRONIZED_BLOCK,
                        SemanticAnnotation.KEY_MONITOR_OBJECT,
                        describeMonitor(monitorObj)));

                // Mark all MONITOR_EXIT instructions for removal
                for (var entry : blockInsns.entrySet()) {
                    for (IrInstruction insn : entry.getValue()) {
                        if (insn.opcode() == IrOpcode.MONITOR_EXIT && matchesObject(insn, monitorObj)) {
                            insn.addAnnotation(SemanticAnnotation.of(
                                    SemanticTag.SYNCHRONIZED_BLOCK));
                        }
                    }
                }
                changed = true;
            }
        }

        return changed;
    }

    /** Build blockId → instructions index. */
    private Map<Integer, List<IrInstruction>> buildBlockIndex(List<IrInstruction> instructions) {
        Map<Integer, List<IrInstruction>> index = new HashMap<>();
        for (IrInstruction insn : instructions) {
            index.computeIfAbsent(insn.blockId(), k -> new ArrayList<>()).add(insn);
        }
        return index;
    }

    /** Find the BasicBlock with the given ID. */
    private BasicBlock findBlock(ControlFlowGraph cfg, int blockId) {
        for (BasicBlock b : cfg.blocks()) {
            if (b.id() == blockId) {
                return b;
            }
        }
        return null;
    }

    /**
     * Find the exception handler (catch-all) that covers the given block.
     */
    private ExceptionRange findCoveringHandler(ControlFlowGraph cfg, BasicBlock block) {
        for (ExceptionRange er : cfg.exceptionRanges()) {
            if (er.catchType() == null && covers(er, block)) {
                // catchType == null means finally / catch-all
                // Check if the handler block contains monitorexit
                return er;
            }
        }
        // Also check typed handlers (they might contain the monitor pattern)
        for (ExceptionRange er : cfg.exceptionRanges()) {
            if (er.catchType() != null && covers(er, block)) {
                return er;
            }
        }
        return null;
    }

    /** Check if the exception range covers the given block. */
    private boolean covers(ExceptionRange er, BasicBlock block) {
        int start = er.startPc();
        int end = er.endPc();
        return block.startOffset() >= start && block.startOffset() < end;
    }

    /**
     * Check if the handler instructions contain MONITOR_EXIT + THROW pattern.
     */
    private boolean isMonitorExitThrow(List<IrInstruction> handlerInsns, Value monitorObj) {
        boolean hasMonitorExit = false;
        boolean hasThrow = false;
        for (IrInstruction insn : handlerInsns) {
            if (insn.opcode() == IrOpcode.MONITOR_EXIT && matchesObject(insn, monitorObj)) {
                hasMonitorExit = true;
            }
            if (insn.opcode() == IrOpcode.THROW) {
                hasThrow = true;
            }
        }
        return hasMonitorExit && hasThrow;
    }

    /** Check if a MONITOR_EXIT instruction references the given monitor object. */
    private boolean matchesObject(IrInstruction monInsn, Value expected) {
        if (monInsn.operands().isEmpty()) {
            return false;
        }
        Value obj = monInsn.operands().getFirst();
        if (expected instanceof Variable ev && obj instanceof Variable ov) {
            return ev.slot() == ov.slot();
        }
        return obj.equals(expected);
    }

    /** Convert a monitor object Value to a readable description. */
    private String describeMonitor(Value v) {
        if (v instanceof Variable var) {
            return "var" + var.slot();
        }
        return v.toString();
    }
}
