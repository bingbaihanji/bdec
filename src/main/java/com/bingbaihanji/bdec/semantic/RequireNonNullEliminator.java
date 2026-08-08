package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Eliminates {@code Objects.requireNonNull()} and {@code Object.getClass()}
 * null-check calls that are compiler-generated artifacts.
 *
 * Inspired by CFR's {@code Op02GetClassRewriter} which detects the
 * {@code DUP; INVOKEVIRTUAL requireNonNull/getClass; POP} bytecode pattern
 * at the instruction level before any analysis.
 *
 * Pattern at IR level:
 * <pre>
 *   LOAD obj        → push obj
 *   INVOKE requireNonNull/getClass(obj) → returns obj
 *   (result consumed by no one, or only by POP)
 * </pre>
 */
public final class RequireNonNullEliminator {

    // Fully-qualified names of methods to eliminate
    private static final String REQUIRE_NON_NULL_CLASS = "java/util/Objects";

    private static final String REQUIRE_NON_NULL_METHOD = "requireNonNull";

    private static final String GET_CLASS_METHOD = "getClass";

    /**
     * Eliminate requireNonNull/getClass null-check calls.
     *
     * @return true if any instructions were removed
     */
    public boolean eliminate(LinearIr ir) {
        boolean changed = false;
        List<IrInstruction> instructions = new ArrayList<>(ir.instructions());

        // First pass: find all INVOKE instructions that call
        // requireNonNull/getClass and whose result is not consumed
        Set<Integer> consumedIds = buildConsumedIds(instructions);
        Set<Integer> toRemove = new HashSet<>();

        for (IrInstruction insn : instructions) {
            if (insn.opcode() != IrOpcode.INVOKE) {
                continue;
            }

            String nameHint = insn.nameHint();
            if (nameHint == null) {
                continue;
            }

            // Check if this is requireNonNull or getClass from the right owner class
            boolean isRequireNonNull = REQUIRE_NON_NULL_METHOD.equals(nameHint);
            boolean isGetClass = GET_CLASS_METHOD.equals(nameHint);
            if (!isRequireNonNull && !isGetClass) {
                continue;
            }

            // For requireNonNull: verify the declaring class is java/util/Objects
            if (isRequireNonNull) {
                var dcAnn = insn.getAnnotation(SemanticTag.DECLARING_CLASS);
                String declaringClass = dcAnn != null
                        ? dcAnn.getString(SemanticAnnotation.KEY_DECLARING_CLASS)
                        : null;
                if (declaringClass == null || !REQUIRE_NON_NULL_CLASS.equals(declaringClass)) {
                    continue; // Not Objects.requireNonNull — could be a different class
                }
            }

            // Check if result is NOT consumed (true null-check pattern)
            if (consumedIds.contains(insn.id())) {
                // Result is used — cannot eliminate (it's a real call)
                continue;
            }

            // Check if the INVOKE has operands (the receiver + args)
            if (insn.operands().isEmpty()) {
                continue;
            }

            // Mark for removal and annotate the first operand (receiver)
            // as the value that should pass through instead of this call
            toRemove.add(insn.id());
            insn.addAnnotation(SemanticAnnotation.of(
                    SemanticTag.NULL_CHECK_REMOVED,
                    SemanticAnnotation.KEY_ORIGINAL_METHOD, nameHint));
            changed = true;
        }

        // Second pass: remove marked instructions
        if (changed) {
            List<IrInstruction> filtered = new ArrayList<>();
            for (IrInstruction insn : instructions) {
                if (!toRemove.contains(insn.id())) {
                    // Also rewrite operands: if an operand references a removed
                    // NULL_CHECK call, replace the ref with the call's receiver
                    filtered.add(rewriteOperands(insn, toRemove, instructions));
                }
            }
            // Replace the instructions list in the LinearIr
            ir.replaceInstructions(filtered);
        }

        return changed;
    }

    /**
     * Build the set of instruction IDs whose results are consumed by other instructions.
     */
    private Set<Integer> buildConsumedIds(List<IrInstruction> instructions) {
        Set<Integer> consumed = new HashSet<>();
        for (IrInstruction insn : instructions) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                }
            }
        }
        return consumed;
    }

    /**
     * Rewrite operands of an instruction: if an operand references a
     * removed null-check call, replace it with the call's receiver.
     */
    private IrInstruction rewriteOperands(IrInstruction insn, Set<Integer> removed,
                                          List<IrInstruction> allInstructions) {
        boolean needsRewrite = false;
        for (Value op : insn.operands()) {
            if (op instanceof InstructionRef ref && removed.contains(ref.instruction().id())) {
                needsRewrite = true;
                break;
            }
        }
        if (!needsRewrite) {
            return insn;
        }

        // Replace operands: for each removed null-check ref, substitute its receiver
        List<Value> newOperands = new ArrayList<>();
        for (Value op : insn.operands()) {
            if (op instanceof InstructionRef ref && removed.contains(ref.instruction().id())) {
                IrInstruction removedInsn = ref.instruction();
                // The receiver is the first operand of the null-check invoke
                if (!removedInsn.operands().isEmpty()) {
                    newOperands.add(removedInsn.operands().getFirst());
                }
            } else {
                newOperands.add(op);
            }
        }

        // Create a new instruction with rewritten operands
        return new IrInstruction(insn.id(), insn.opcode(), insn.resultType(),
                newOperands, insn.sourceOffset(), insn.blockId(),
                insn.originalOpcode(), insn.nameHint());
    }
}
