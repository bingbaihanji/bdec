package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.opcode.Opcode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes JVM bytecode instructions from a DataInputStream.
 */
public final class InstructionDecoder {

    /**
     * Decode one instruction at the current stream position.
     * Returns null for unrecognized opcodes (should not happen with valid class files).
     */
    public Instruction decode(DataInputStream in, int offset) throws IOException {
        int opcodeByte = in.readUnsignedByte();
        Opcode op;
        try {
            op = Opcode.byCode(opcodeByte);
        } catch (IllegalArgumentException e) {
            System.err.println("WARNING: unknown opcode " + opcodeByte + " at offset " + offset);
            return null;
        }

        List<Integer> operands = new ArrayList<>();
        int[] jumpTargets = new int[0];
        int varIndex = op.implicitVarIndex();

        switch (op.operandBytes()) {
            case 1 -> {
                int val = in.readUnsignedByte();
                operands.add(val);
                if (op.implicitVarIndex() == 0) {
                    varIndex = val;
                }
            }
            case 2 -> {
                int val = in.readUnsignedShort();
                operands.add(val);
                if (op.implicitVarIndex() == 0) {
                    varIndex = val;
                }
                // Branch instructions: signed 16-bit offset from the instruction start
                if (op.isConditional() || op == Opcode.GOTO || op == Opcode.JSR) {
                    short branchOffset = (short) val;
                    jumpTargets = new int[]{offset + branchOffset};
                }
            }
            case 4 -> {
                // INVOKEINTERFACE: 2 bytes index + 1 byte count + 1 byte 0
                if (op == Opcode.INVOKEINTERFACE) {
                    int index = in.readUnsignedShort();
                    int count = in.readUnsignedByte();
                    int zero = in.readUnsignedByte();
                    operands.add(index);
                    operands.add(count);
                } else {
                    int val = in.readInt();
                    operands.add(val);
                }
            }
            case 0 -> {
                // No operands
            }
            default -> {
                // Should not reach here
            }
        }

        return new Instruction(offset, opcodeByte, op.mnemonic(),
                operands, op.canFallThrough(), op.isTerminal(), jumpTargets, varIndex);
    }

    /**
     * Decode all instructions from a byte array range.
     */
    public List<Instruction> decodeAll(byte[] code, int startPc, int length) throws IOException {
        List<Instruction> instructions = new ArrayList<>();
        ByteArrayInputStream bis = new ByteArrayInputStream(code, startPc, length);
        DataInputStream dis = new DataInputStream(bis);
        int offset = startPc;
        while (dis.available() > 0) {
            Instruction insn = decode(dis, offset);
            if (insn == null) {
                break;
            }
            instructions.add(insn);
            offset = startPc + length - dis.available();
        }
        return instructions;
    }
}
