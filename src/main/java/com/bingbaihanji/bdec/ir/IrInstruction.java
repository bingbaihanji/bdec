package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.semantic.SemanticAnnotation;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IrInstruction {

    private final int id;

    private final IrOpcode opcode;

    private final JavaType resultType;

    private final List<Value> operands;

    private final int sourceOffset;

    private final int blockId;

    /** Original JVM bytecode opcode (e.g. 0x60=IADD, 0x64=ISUB).
     *  Zero means no original opcode (synthetic PHI, etc.). */
    private final int originalOpcode;

    /** Resolved name hint — field name or method name from constant pool. */
    private final String nameHint;

    /** Semantic annotations attached by the SemanticReconstructor pipeline. */
    private List<SemanticAnnotation> annotations;

    private Value resultValue;

    public IrInstruction(int id, IrOpcode opcode, JavaType resultType,
                         List<Value> operands, int sourceOffset, int blockId,
                         int originalOpcode, String nameHint) {
        this.id = id;
        this.opcode = opcode;
        this.resultType = resultType;
        this.operands = List.copyOf(operands);
        this.sourceOffset = sourceOffset;
        this.blockId = blockId;
        this.originalOpcode = originalOpcode;
        this.nameHint = nameHint;
    }

    /** Backward-compatible constructor without metadata. */
    public IrInstruction(int id, IrOpcode opcode, JavaType resultType,
                         List<Value> operands, int sourceOffset, int blockId) {
        this(id, opcode, resultType, operands, sourceOffset, blockId, 0, null);
    }

    // --- Factory methods ---

    public static IrInstruction load(int id, Variable var, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.LOAD, var.type(), List.of(var),
                offset, blockId, 0, null);
    }

    public static IrInstruction store(int id, Variable var, Value value, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.STORE, var.type(), List.of(var, value),
                offset, blockId, 0, null);
    }

    public static IrInstruction binary(int id, IrOpcode op, Value left, Value right,
                                       JavaType resultType, int offset, int blockId,
                                       int originalOpcode) {
        return new IrInstruction(id, op, resultType, List.of(left, right),
                offset, blockId, originalOpcode, null);
    }

    /** Backward-compatible binary factory. */
    public static IrInstruction binary(int id, IrOpcode op, Value left, Value right,
                                       JavaType resultType, int offset, int blockId) {
        return binary(id, op, left, right, resultType, offset, blockId, 0);
    }

    public static IrInstruction invoke(int id, Value target, List<Value> args,
                                       JavaType returnType, int offset, int blockId,
                                       String methodName) {
        List<Value> operands = new java.util.ArrayList<>();
        if (target != null) {
            operands.add(target);
        }
        operands.addAll(args);
        return new IrInstruction(id, IrOpcode.INVOKE, returnType, operands,
                offset, blockId, 0, methodName);
    }

    public static IrInstruction invoke(int id, Value target, List<Value> args,
                                       JavaType returnType, int offset, int blockId) {
        return invoke(id, target, args, returnType, offset, blockId, null);
    }

    public static IrInstruction fieldLoad(int id, Value obj, JavaType fieldType,
                                          int offset, int blockId, String fieldName) {
        List<Value> ops = obj != null ? List.of(obj) : List.of();
        return new IrInstruction(id, IrOpcode.FIELD_LOAD, fieldType, ops,
                offset, blockId, 0, fieldName);
    }

    public static IrInstruction fieldLoad(int id, Value obj, JavaType fieldType,
                                          int offset, int blockId) {
        return fieldLoad(id, obj, fieldType, offset, blockId, null);
    }

    public static IrInstruction fieldStore(int id, Value obj, Value value,
                                           int offset, int blockId, String fieldName) {
        List<Value> ops = obj != null ? List.of(obj, value) : List.of(value);
        return new IrInstruction(id, IrOpcode.FIELD_STORE, value.type(), ops,
                offset, blockId, 0, fieldName);
    }

    public static IrInstruction fieldStore(int id, Value obj, Value value,
                                           int offset, int blockId) {
        return fieldStore(id, obj, value, offset, blockId, null);
    }

    public static IrInstruction returnInsn(int id, Value value, int offset, int blockId) {
        List<Value> ops = value != null ? List.of(value) : List.of();
        JavaType t = value != null ? value.type() : JavaType.VOID;
        return new IrInstruction(id, IrOpcode.RETURN, t, ops, offset, blockId, 0, null);
    }

    public static IrInstruction newInsn(int id, JavaType type, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.NEW, type, List.of(), offset, blockId, 0, null);
    }

    public static IrInstruction cast(int id, Value value, JavaType targetType,
                                     int offset, int blockId, int originalOpcode) {
        return new IrInstruction(id, IrOpcode.CAST, targetType, List.of(value),
                offset, blockId, originalOpcode, null);
    }

    public static IrInstruction cast(int id, Value value, JavaType targetType,
                                     int offset, int blockId) {
        return cast(id, value, targetType, offset, blockId, 0);
    }

    public static IrInstruction constInsn(int id, ConstantValue value, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.CONST, value.type(), List.of(value),
                offset, blockId, 0, null);
    }

    // --- Getters ---

    /**
     * Map a JVM bytecode opcode to the corresponding BinaryOperator.
     * Returns null for non-binary/comparison opcodes.
     */
    public static BinaryOperator binaryOpFromBytecode(int bc) {
        return switch (bc) {
            // Arithmetic
            case 0x60 -> BinaryOperator.ADD;    // IADD
            case 0x64 -> BinaryOperator.SUB;    // ISUB
            case 0x68 -> BinaryOperator.MUL;    // IMUL
            case 0x6c -> BinaryOperator.DIV;    // IDIV
            case 0x70 -> BinaryOperator.REM;    // IREM
            case 0x61 -> BinaryOperator.ADD;    // LADD
            case 0x65 -> BinaryOperator.SUB;    // LSUB
            case 0x69 -> BinaryOperator.MUL;    // LMUL
            case 0x6d -> BinaryOperator.DIV;    // LDIV
            case 0x71 -> BinaryOperator.REM;    // LREM
            case 0x62 -> BinaryOperator.ADD;    // FADD
            case 0x66 -> BinaryOperator.SUB;    // FSUB
            case 0x6a -> BinaryOperator.MUL;    // FMUL
            case 0x6e -> BinaryOperator.DIV;    // FDIV
            case 0x72 -> BinaryOperator.REM;    // FREM
            case 0x63 -> BinaryOperator.ADD;    // DADD
            case 0x67 -> BinaryOperator.SUB;    // DSUB
            case 0x6b -> BinaryOperator.MUL;    // DMUL
            case 0x6f -> BinaryOperator.DIV;    // DDIV
            case 0x73 -> BinaryOperator.REM;    // DREM
            // Bitwise
            case 0x7e -> BinaryOperator.BIT_AND; // IAND
            case 0x7f -> BinaryOperator.BIT_AND; // LAND
            case 0x80 -> BinaryOperator.BIT_OR;  // IOR
            case 0x81 -> BinaryOperator.BIT_OR;  // LOR
            case 0x82 -> BinaryOperator.BIT_XOR; // IXOR
            case 0x83 -> BinaryOperator.BIT_XOR; // LXOR
            // Shift
            case 0x78 -> BinaryOperator.SHL;     // ISHL
            case 0x79 -> BinaryOperator.SHL;     // LSHL
            case 0x7a -> BinaryOperator.SHR;     // ISHR
            case 0x7b -> BinaryOperator.SHR;     // LSHR
            case 0x7c -> BinaryOperator.USHR;    // IUSHR
            case 0x7d -> BinaryOperator.USHR;    // LUSHR
            // Comparisons (int)
            case 0x9f -> BinaryOperator.EQ;      // IF_ICMPEQ
            case 0xa0 -> BinaryOperator.NE;      // IF_ICMPNE
            case 0xa1 -> BinaryOperator.LT;      // IF_ICMPLT
            case 0xa2 -> BinaryOperator.GE;      // IF_ICMPGE
            case 0xa3 -> BinaryOperator.GT;      // IF_ICMPGT
            case 0xa4 -> BinaryOperator.LE;      // IF_ICMPLE
            // Comparisons (ref)
            case 0xa5 -> BinaryOperator.EQ;      // IF_ACMPEQ
            case 0xa6 -> BinaryOperator.NE;      // IF_ACMPNE
            // Zero comparisons
            case 0x99 -> BinaryOperator.EQ;      // IFEQ
            case 0x9a -> BinaryOperator.NE;      // IFNE
            case 0x9b -> BinaryOperator.LT;      // IFLT
            case 0x9c -> BinaryOperator.GE;      // IFGE
            case 0x9d -> BinaryOperator.GT;      // IFGT
            case 0x9e -> BinaryOperator.LE;      // IFLE
            default -> null;
        };
    }

    public int id() {return id;}

    public IrOpcode opcode() {return opcode;}

    public JavaType resultType() {return resultType;}

    public List<Value> operands() {return operands;}

    public int sourceOffset() {return sourceOffset;}

    public int blockId() {return blockId;}

    public int originalOpcode() {return originalOpcode;}

    /** Resolved field or method name, or null if not resolved. */
    public String nameHint() {return nameHint;}

    /** Semantic annotations attached by the semantic reconstruction pipeline. */
    public List<SemanticAnnotation> annotations() {
        return annotations != null ? annotations : Collections.emptyList();
    }

    /** Add a semantic annotation to this instruction. */
    public void addAnnotation(SemanticAnnotation ann) {
        if (annotations == null) {
            annotations = new ArrayList<>(2);
        }
        annotations.add(ann);
    }

    /** Check if this instruction has a specific semantic tag. */
    public boolean hasTag(com.bingbaihanji.bdec.semantic.SemanticTag tag) {
        if (annotations == null) {
            return false;
        }
        for (SemanticAnnotation a : annotations) {
            if (a.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Get the first annotation with the given tag, or null. */
    public SemanticAnnotation getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag tag) {
        if (annotations == null) {
            return null;
        }
        for (SemanticAnnotation a : annotations) {
            if (a.is(tag)) {
                return a;
            }
        }
        return null;
    }

    public Value resultValue() {return resultValue;}

    // --- Operator inference from bytecode opcode ---

    public void setResultValue(Value v) {this.resultValue = v;}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(": ").append(opcode);
        if (nameHint != null) {
            sb.append(" '").append(nameHint).append("'");
        }
        sb.append(" ").append(operands).append(" -> ").append(resultType);
        return sb.toString();
    }
}
