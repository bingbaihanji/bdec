package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.type.TypeKind;

/**
 * Folds integer constants (0, 1) into boolean {@code false/true}
 * when the surrounding context has a boolean type.
 *
 * Inspired by CFR's {@code ComparisonOperation.isBooleanComparison()}
 * and Procyon's {@code TypeAnalysis.verifyResults()} boolean constant folding.
 */
public final class TypeAwareConstantFolder {

    /** Follow InstructionRef chains (including PHI nodes) to find ConstantValue. */
    private static ConstantValue unwrapConstant(Value v) {
        if (v instanceof ConstantValue cv) {
            return cv;
        }
        if (v instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            // Direct CONST
            if (def.opcode() == IrOpcode.CONST && !def.operands().isEmpty()
                    && def.operands().getFirst() instanceof ConstantValue cv) {
                return cv;
            }
            // PHI → pick first operand and recurse
            if (def.opcode() == IrOpcode.PHI && !def.operands().isEmpty()) {
                return unwrapConstant(def.operands().getFirst());
            }
        }
        return null;
    }

    /**
     * Fold boolean constants in a method's IR.
     *
     * Only folds constants in unambiguous boolean contexts
     * (boolean method returns). Does NOT fold all 0/1 constants
     * — regular int arithmetic results must stay as ints.
     *
     * @return true if any changes were made
     */
    public boolean fold(LinearIr ir, MethodModel method) {
        boolean changed = false;
        boolean isBooleanReturn = method.returnType() != null
                && method.returnType().kind() == TypeKind.BOOLEAN;

        for (IrInstruction insn : ir.instructions()) {
            // Boolean return folding — only in methods that return boolean
            if (isBooleanReturn && insn.opcode() == IrOpcode.RETURN) {
                changed |= foldBooleanReturn(insn);
            }
        }
        return changed;
    }

    /**
     * Fold RETURN with 0/1 operand in boolean methods to true/false.
     */
    private boolean foldBooleanReturn(IrInstruction ret) {
        if (ret.operands().isEmpty()) {
            return false;
        }

        Value operand = ret.operands().getFirst();
        // Follow InstructionRef chains (constants are now emitted as CONST IR)
        ConstantValue cv = unwrapConstant(operand);
        if (cv != null) {
            Object val = cv.value();
            if (val instanceof Integer i) {
                boolean boolVal = i != 0;
                ret.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.BOOLEAN_RETURN,
                        SemanticAnnotation.KEY_BOOLEAN_VALUE, boolVal));
                return true;
            }
            if (val instanceof Long l) {
                ret.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.BOOLEAN_RETURN,
                        SemanticAnnotation.KEY_BOOLEAN_VALUE, l != 0L));
                return true;
            }
        }
        return false;
    }
}
