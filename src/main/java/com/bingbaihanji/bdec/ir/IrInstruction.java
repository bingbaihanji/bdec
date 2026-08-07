package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

public class IrInstruction {

    private final int id;

    private final IrOpcode opcode;

    private final JavaType resultType;

    private final List<Value> operands;

    private final int sourceOffset;

    private final int blockId;

    private Value resultValue;

    public IrInstruction(int id, IrOpcode opcode, JavaType resultType,
                         List<Value> operands, int sourceOffset, int blockId) {
        this.id = id;
        this.opcode = opcode;
        this.resultType = resultType;
        this.operands = List.copyOf(operands);
        this.sourceOffset = sourceOffset;
        this.blockId = blockId;
    }

    // --- Factory methods ---
    public static IrInstruction load(int id, Variable var, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.LOAD, var.type(), List.of(var), offset, blockId);
    }

    public static IrInstruction store(int id, Variable var, Value value, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.STORE, var.type(), List.of(var, value), offset, blockId);
    }

    public static IrInstruction binary(int id, IrOpcode op, Value left, Value right,
                                       JavaType resultType, int offset, int blockId) {
        return new IrInstruction(id, op, resultType, List.of(left, right), offset, blockId);
    }

    public static IrInstruction invoke(int id, Value target, List<Value> args,
                                       JavaType returnType, int offset, int blockId) {
        List<Value> operands = new java.util.ArrayList<>();
        if (target != null) {
            operands.add(target);
        }
        operands.addAll(args);
        return new IrInstruction(id, IrOpcode.INVOKE, returnType, operands, offset, blockId);
    }

    public static IrInstruction fieldLoad(int id, Value obj, JavaType fieldType,
                                          int offset, int blockId) {
        List<Value> ops = obj != null ? List.of(obj) : List.of();
        return new IrInstruction(id, IrOpcode.FIELD_LOAD, fieldType, ops, offset, blockId);
    }

    public static IrInstruction fieldStore(int id, Value obj, Value value,
                                           int offset, int blockId) {
        List<Value> ops = obj != null ? List.of(obj, value) : List.of(value);
        return new IrInstruction(id, IrOpcode.FIELD_STORE, value.type(), ops, offset, blockId);
    }

    public static IrInstruction returnInsn(int id, Value value, int offset, int blockId) {
        List<Value> ops = value != null ? List.of(value) : List.of();
        JavaType t = value != null ? value.type() : com.bingbaihanji.bdec.type.JavaType.VOID;
        return new IrInstruction(id, IrOpcode.RETURN, t, ops, offset, blockId);
    }

    public static IrInstruction newInsn(int id, JavaType type, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.NEW, type, List.of(), offset, blockId);
    }

    public static IrInstruction cast(int id, Value value, JavaType targetType, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.CAST, targetType, List.of(value), offset, blockId);
    }

    public static IrInstruction constInsn(int id, ConstantValue value, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.CONST, value.type(), List.of(value), offset, blockId);
    }

    public int id() {return id;}

    public IrOpcode opcode() {return opcode;}

    public JavaType resultType() {return resultType;}

    public List<Value> operands() {return operands;}

    public int sourceOffset() {return sourceOffset;}

    public int blockId() {return blockId;}

    public Value resultValue() {return resultValue;}

    public void setResultValue(Value v) {this.resultValue = v;}

    @Override
    public String toString() {
        return id + ": " + opcode + " " + operands + " -> " + resultType;
    }
}
