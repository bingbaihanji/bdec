package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Type inference pass for SSA-form IR.
 *
 * Propagates types through the IR using a worklist algorithm.
 * Seeds types from constants, field accesses, and method calls,
 * then propagates through assignments, PHI nodes, and arithmetic.
 */
public final class TypeInference {

    private final Map<Integer, JavaType> types = new HashMap<>(); // instruction id → type

    private static int rank(JavaType t) {
        return switch (t.kind()) {
            case BYTE -> 0;
            case SHORT -> 1;
            case CHAR -> 1;
            case INT -> 2;
            case LONG -> 3;
            case FLOAT -> 4;
            case DOUBLE -> 5;
            default -> -1;
        };
    }

    /**
     * Run type inference on an SSA IR, returning a map from instruction ID to inferred type.
     */
    public Map<Integer, JavaType> infer(SsaForm ssa) {
        types.clear();
        Deque<IrInstruction> worklist = new ArrayDeque<>();

        // Seed: assign initial types from known sources
        for (IrInstruction insn : ssa.instructions()) {
            JavaType seed = seedType(insn);
            if (seed != null) {
                types.put(insn.id(), seed);
                worklist.add(insn);
            }
        }

        // Propagate
        while (!worklist.isEmpty()) {
            IrInstruction insn = worklist.poll();
            JavaType currentType = types.get(insn.id());
            if (currentType == null) {
                continue;
            }

            // Propagate to consumers
            for (IrInstruction other : ssa.instructions()) {
                if (other == insn) {
                    continue;
                }
                // If 'other' uses this instruction's result, propagate type
                for (Value op : other.operands()) {
                    if (op instanceof InstructionRef ref && ref.instruction().id() == insn.id()) {
                        JavaType propagated = propagate(insn.opcode(), currentType, other.opcode());
                        if (propagated != null) {
                            JavaType existing = types.get(other.id());
                            JavaType merged = existing != null ? merge(existing, propagated) : propagated;
                            if (!merged.equals(existing)) {
                                types.put(other.id(), merged);
                                worklist.add(other);
                            }
                        }
                    }
                }
            }
        }

        return Map.copyOf(types);
    }

    /** Determine the initial type for an instruction. */
    private JavaType seedType(IrInstruction insn) {
        return switch (insn.opcode()) {
            case CONST -> {
                if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof ConstantValue cv) {
                    yield cv.type();
                }
                yield null;
            }
            case FIELD_LOAD -> insn.resultType();
            case INVOKE -> insn.resultType();
            case NEW, NEW_ARRAY -> insn.resultType();
            case ARRAY_LENGTH -> JavaType.INT;
            case INSTANCE_OF -> JavaType.INT;
            case COMPARE -> JavaType.INT;
            case BINARY -> insn.resultType();
            case CAST -> insn.resultType(); // trust the explicit cast
            case PHI -> null; // will be inferred from predecessors
            default -> null;
        };
    }

    /** Propagate type through an operation. */
    private JavaType propagate(IrOpcode producerOp, JavaType producerType, IrOpcode consumerOp) {
        return switch (consumerOp) {
            case STORE, RETURN -> producerType;
            case BINARY -> {
                // Binary arithmetic preserves the wider type
                if (producerType.kind() == TypeKind.DOUBLE) {
                    yield JavaType.DOUBLE;
                }
                if (producerType.kind() == TypeKind.FLOAT) {
                    yield JavaType.FLOAT;
                }
                if (producerType.kind() == TypeKind.LONG) {
                    yield JavaType.LONG;
                }
                yield JavaType.INT;
            }
            case COMPARE, CONDITION -> JavaType.INT;
            case PHI -> producerType;
            case INVOKE -> producerType; // pass-through as argument
            default -> producerType;
        };
    }

    /** Merge two types (least upper bound). */
    private JavaType merge(JavaType a, JavaType b) {
        if (a.equals(b)) {
            return a;
        }
        // Numeric widening hierarchy: byte < short < int < long < float < double
        if (isNumeric(a) && isNumeric(b)) {
            return wider(a, b);
        }
        // Object types: use common supertype (simplified: java/lang/Object)
        if (a.kind() == TypeKind.CLASS && b.kind() == TypeKind.CLASS) {
            return JavaType.classType("java/lang/Object");
        }
        return a; // keep the first type as fallback
    }

    private boolean isNumeric(JavaType t) {
        return switch (t.kind()) {
            case BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    private JavaType wider(JavaType a, JavaType b) {
        return rank(a) >= rank(b) ? a : b;
    }
}
