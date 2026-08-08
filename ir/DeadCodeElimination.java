package com.bingbaihanji.bdec.ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dead code elimination: removes instructions whose results are never used.
 * Uses a mark-and-sweep approach starting from "live" instructions
 * (returns, stores, throws, invocations with side effects).
 */
public final class DeadCodeElimination {

    /**
     * Eliminate dead instructions. Returns a new list with dead code removed.
     */
    public List<IrInstruction> eliminate(List<IrInstruction> instructions) {
        // 1. Mark: find all instructions that are transitively used from live roots
        Set<Integer> live = new HashSet<>();
        Deque<IrInstruction> worklist = new ArrayDeque<>();

        // Seed: instructions with side effects or that produce observable values
        for (IrInstruction insn : instructions) {
            if (isLiveRoot(insn)) {
                live.add(insn.id());
                worklist.add(insn);
            }
        }

        // Mark all operands of live instructions as live
        while (!worklist.isEmpty()) {
            IrInstruction insn = worklist.poll();
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    IrInstruction def = ref.instruction();
                    if (live.add(def.id())) {
                        worklist.add(def);
                    }
                }
            }
        }

        // 2. Sweep: keep only live instructions
        List<IrInstruction> result = new ArrayList<>();
        for (IrInstruction insn : instructions) {
            if (live.contains(insn.id())) {
                result.add(insn);
            }
        }

        return result;
    }

    /** Check if an instruction is a "root" that must be preserved. */
    private boolean isLiveRoot(IrInstruction insn) {
        return switch (insn.opcode()) {
            case RETURN, THROW, STORE, FIELD_STORE, ARRAY_STORE -> true;
            case INVOKE -> true; // method calls may have side effects
            case MONITOR_ENTER, MONITOR_EXIT -> true;
            case CONDITION -> true; // controls branching
            case SWITCH -> true;
            default -> false;
        };
    }
}
