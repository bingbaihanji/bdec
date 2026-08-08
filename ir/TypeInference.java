package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Type inference pass for SSA-form IR.
 *
 * Propagates types through the IR using a worklist algorithm.
 * Seeds types from constants, field accesses, and method calls,
 * then propagates through assignments, PHI nodes, and arithmetic.
 *
 * Uses a def-use chain index for O(1) consumer lookups instead of
 * O(n²) scanning.
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

        // Build def-use chain: for each instruction, find consumers of its result
        Map<Integer, List<IrInstruction>> consumers = buildDefUse(ssa.instructions());

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

            // Propagate to consumers of this instruction
            List<IrInstruction> users = consumers.getOrDefault(insn.id(), List.of());
            for (IrInstruction user : users) {
                JavaType propagated = propagate(insn.opcode(), currentType, user.opcode());
                if (propagated != null) {
                    JavaType existing = types.get(user.id());
                    JavaType merged = existing != null ? merge(existing, propagated) : propagated;
                    if (!merged.equals(existing)) {
                        types.put(user.id(), merged);
                        worklist.add(user);
                    }
                }
            }
        }

        return Map.copyOf(types);
    }

    /**
     * Build a map from instruction ID → list of instructions that use its result.
     */
    private Map<Integer, List<IrInstruction>> buildDefUse(List<IrInstruction> instructions) {
        Map<Integer, List<IrInstruction>> consumers = new HashMap<>();
        for (IrInstruction insn : instructions) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    int defId = ref.instruction().id();
                    consumers.computeIfAbsent(defId, k -> new ArrayList<>()).add(insn);
                }
            }
        }
        return consumers;
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
            case INVOKE -> producerType;
            default -> producerType;
        };
    }

    /** Merge two types (least upper bound). */
    private JavaType merge(JavaType a, JavaType b) {
        if (a.equals(b)) {
            return a;
        }
        if (isNumeric(a) && isNumeric(b)) {
            return wider(a, b);
        }
        if (a.kind() == TypeKind.CLASS && b.kind() == TypeKind.CLASS) {
            return JavaType.classType("java/lang/Object");
        }
        return a;
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
