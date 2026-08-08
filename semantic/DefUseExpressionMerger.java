package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges single-use intermediate expressions across basic blocks
 * using simple def-use analysis.
 *
 * Inspired by CFR's {@code LValueProp} (copy propagation) and
 * Procyon's {@code CopyPropagation} pass.
 *
 * For each STORE instruction that defines a value used exactly once:
 * - Inlines the stored value into the use site
 * - Removes the STORE instruction
 * - If the defining instruction is also single-use, recursively inlines it
 */
public final class DefUseExpressionMerger {

    /**
     * Merge single-use expressions.
     *
     * @return true if any expressions were merged
     */
    public boolean merge(LinearIr ir) {
        boolean changed = false;
        List<IrInstruction> instructions = new ArrayList<>(ir.instructions());

        // Build def-use counts
        Map<Integer, Integer> useCount = new HashMap<>();
        Map<Integer, List<Integer>> useSites = new HashMap<>(); // defId → [useInsnId]

        for (IrInstruction insn : instructions) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    int defId = ref.instruction().id();
                    useCount.merge(defId, 1, Integer::sum);
                    useSites.computeIfAbsent(defId, k -> new ArrayList<>()).add(insn.id());
                }
            }
        }

        // Find single-use STORE instructions
        Set<Integer> toRemove = new HashSet<>();
        Map<Integer, Value> replacements = new HashMap<>(); // defId → replacement value

        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.STORE) {
                int useCnt = useCount.getOrDefault(insn.id(), 0);
                if (useCnt == 1 && insn.operands().size() >= 2) {
                    // STORE has operands: [targetVar, sourceValue]
                    Value source = insn.operands().get(1);
                    toRemove.add(insn.id());
                    replacements.put(insn.id(), source);
                    insn.addAnnotation(SemanticAnnotation.of(SemanticTag.SINGLE_USE_INLINE));
                    changed = true;
                }
            }

            // Also inline simple LOAD+single-use patterns:
            // If an instruction produces a value used exactly once,
            // and the defining instruction is a simple expression (BINARY, CAST, etc.),
            // mark it for inlining.
            if (isSimpleExpr(insn)) {
                int useCnt = useCount.getOrDefault(insn.id(), 0);
                if (useCnt == 1) {
                    insn.addAnnotation(SemanticAnnotation.of(SemanticTag.SINGLE_USE_INLINE));
                }
            }
        }

        // Remove marked STORE instructions
        if (changed) {
            List<IrInstruction> filtered = new ArrayList<>();
            for (IrInstruction insn : instructions) {
                if (toRemove.contains(insn.id())) {
                    continue;
                }
                // Rewrite operands: if operand refs a replaced def, substitute
                filtered.add(substituteOperands(insn, replacements));
            }
            ir.replaceInstructions(filtered);
        }

        return changed;
    }

    /** Check if an instruction is a simple expression (no side effects). */
    private boolean isSimpleExpr(IrInstruction insn) {
        return switch (insn.opcode()) {
            case BINARY, UNARY, CAST, FIELD_LOAD, ARRAY_LOAD,
                 ARRAY_LENGTH, INSTANCE_OF, CONST -> true;
            default -> false;
        };
    }

    /** Replace operand references with their inlined values. */
    private IrInstruction substituteOperands(IrInstruction insn,
                                             Map<Integer, Value> replacements) {
        boolean changed = false;
        List<Value> newOps = new ArrayList<>();
        for (Value op : insn.operands()) {
            if (op instanceof InstructionRef ref
                    && replacements.containsKey(ref.instruction().id())) {
                newOps.add(replacements.get(ref.instruction().id()));
                changed = true;
            } else {
                newOps.add(op);
            }
        }
        if (!changed) {
            return insn;
        }

        return new IrInstruction(insn.id(), insn.opcode(), insn.resultType(),
                newOps, insn.sourceOffset(), insn.blockId(),
                insn.originalOpcode(), insn.nameHint());
    }
}
