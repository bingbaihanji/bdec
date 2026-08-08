package com.bingbaihanji.bdec.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copy propagation: replaces variable references with their defining values
 * when the variable is only assigned once and the value is a simple constant
 * or another variable.
 */
public final class CopyPropagation {

    /**
     * Run copy propagation on an IR instruction list.
     * Returns a new list with copies replaced.
     */
    public List<IrInstruction> propagate(List<IrInstruction> instructions) {
        // Map from variable slot → defining value (if single-def and simple)
        Map<Integer, Value> copyMap = new HashMap<>();
        Map<Integer, IrInstruction> defInsn = new HashMap<>();

        // First pass: find single-def variables
        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2) {
                Value target = insn.operands().get(0);
                Value source = insn.operands().get(1);
                if (target instanceof Variable v) {
                    int slot = v.slot();
                    if (defInsn.containsKey(slot)) {
                        // Multiple definitions — can't propagate safely
                        copyMap.remove(slot);
                    } else if (isPropagable(source)) {
                        copyMap.put(slot, source);
                        defInsn.put(slot, insn);
                    }
                }
            }
        }

        if (copyMap.isEmpty()) {
            return instructions;
        }

        // Second pass: replace LOAD references with the propagated value
        List<IrInstruction> result = new ArrayList<>();
        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.LOAD && insn.operands().size() == 1
                    && insn.operands().getFirst() instanceof Variable v) {
                Value replacement = copyMap.get(v.slot());
                if (replacement != null) {
                    // Replace LOAD with the source value
                    // We keep the instruction but update the operands downstream
                    IrInstruction replaced = new IrInstruction(insn.id(), insn.opcode(),
                            insn.resultType(), List.of(replacement), insn.sourceOffset(), insn.blockId());
                    replaced.setResultValue(new InstructionRef(replaced, insn.resultType()));
                    result.add(replaced);
                    continue;
                }
            }
            result.add(insn);
        }

        return result;
    }

    /** Check if a value is safe to propagate (constant or another variable). */
    private boolean isPropagable(Value v) {
        return v instanceof ConstantValue || v instanceof Variable;
    }
}
