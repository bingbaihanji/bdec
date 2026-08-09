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

        // Tableswitch/Lookupswitch: variable-length, special handling
        if (op == Opcode.TABLESWITCH || op == Opcode.LOOKUPSWITCH) {
            return decodeSwitch(in, offset, op);
        }

        // WIDE: extends the next instruction's local variable index to u2.
        // WIDE + IINC: 2-byte index + 2-byte signed const.
        // WIDE + ILOAD/FLOAD/ALOAD/LLOAD/DLOAD/ISTORE/FSTORE/ASTORE/LSTORE/DSTORE/RET: 2-byte index.
        if (op == Opcode.WIDE) {
            return decodeWide(in, offset);
        }

        // IINC: 2 bytes but they are TWO separate u1 values (index + const),
        // NOT one u2. Handle before the general case 2 branch.
        if (op == Opcode.IINC) {
            int index = in.readUnsignedByte();
            int incr = in.readByte(); // signed
            operands.add(index);
            operands.add(incr);
            varIndex = index;
            return new Instruction(offset, opcodeByte, op.mnemonic(),
                    operands, op.canFallThrough(), op.isTerminal(), jumpTargets, varIndex);
        }

        switch (op.operandBytes()) {
            case 1 -> {
                int val = in.readUnsignedByte();
                operands.add(val);
                if (op.implicitVarIndex() < 0) {
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
                // INVOKEDYNAMIC: 2 bytes index + 2 bytes 0 (must be zero)
                // GOTO_W/JSR_W: 4 bytes signed branch offset
                if (op == Opcode.INVOKEINTERFACE) {
                    int index = in.readUnsignedShort();
                    int count = in.readUnsignedByte();
                    int zero = in.readUnsignedByte();
                    operands.add(index);
                    operands.add(count);
                } else if (op == Opcode.INVOKEDYNAMIC) {
                    int index = in.readUnsignedShort();
                    int zero1 = in.readUnsignedByte();
                    int zero2 = in.readUnsignedByte();
                    operands.add(index); // only the CP index matters
                } else if (op == Opcode.GOTO_W || op == Opcode.JSR_W) {
                    int branchOffset = in.readInt();
                    operands.add(branchOffset);
                    jumpTargets = new int[]{offset + branchOffset};
                } else {
                    int val = in.readInt();
                    operands.add(val);
                }
            }
            case 3 -> {
                // MULTIANEWARRAY: 2 bytes CP index + 1 byte dimensions
                int index = in.readUnsignedShort();
                int dims = in.readUnsignedByte();
                operands.add(index);
                operands.add(dims);
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

    /** Decode a tableswitch or lookupswitch instruction. */
    private Instruction decodeSwitch(DataInputStream in, int offset, Opcode op) throws IOException {
        // Skip 0-3 padding bytes to reach 4-byte alignment from method start
        int alignedOffset = (offset + 4) & ~3;
        int skipBytes = alignedOffset - offset - 1; // -1 because we already read the opcode byte
        if (skipBytes > 0) {
            in.skipBytes(skipBytes);
        }

        int defaultTarget = in.readInt();
        List<Integer> operands = new ArrayList<>();
        List<Integer> jumpTargetsList = new ArrayList<>();
        jumpTargetsList.add(offset + defaultTarget);

        if (op == Opcode.TABLESWITCH) {
            int low = in.readInt();
            int high = in.readInt();
            operands.add(defaultTarget);
            operands.add(low);
            operands.add(high);
            int count = high - low + 1;
            for (int i = 0; i < count; i++) {
                int caseOffset = in.readInt();
                jumpTargetsList.add(offset + caseOffset);
            }
        } else {
            // LOOKUPSWITCH
            int npairs = in.readInt();
            operands.add(defaultTarget);
            operands.add(npairs);
            for (int i = 0; i < npairs; i++) {
                int match = in.readInt();
                int caseOffset = in.readInt();
                operands.add(match);
                jumpTargetsList.add(offset + caseOffset);
            }
        }

        int[] jumpTargets = jumpTargetsList.stream().mapToInt(Integer::intValue).toArray();
        return new Instruction(offset, op.code(), op.mnemonic(),
                operands, op.canFallThrough(), op.isTerminal(), jumpTargets, varIndex(op));
    }

    /** Decode a WIDE-prefixed instruction. */
    private Instruction decodeWide(DataInputStream in, int offset) throws IOException {
        int widenedOpcode = in.readUnsignedByte();
        Opcode widenedOp;
        try {
            widenedOp = Opcode.byCode(widenedOpcode);
        } catch (IllegalArgumentException e) {
            System.err.println("WARNING: unknown widened opcode " + widenedOpcode + " at offset " + offset);
            return null;
        }

        int widenedIndex = in.readUnsignedShort(); // 2-byte index
        List<Integer> operands = new ArrayList<>();
        operands.add(widenedIndex);

        // WIDE + IINC: 2-byte index + 2-byte signed const
        if (widenedOp == Opcode.IINC) {
            int incr = in.readShort(); // signed
            operands.add(incr);
        }

        // Use the widened opcode (not 196) so IrBuilder dispatches correctly.
        // The mnemonic keeps the "wide" prefix for debugging.
        return new Instruction(offset, widenedOpcode, "wide " + widenedOp.mnemonic(),
                operands, widenedOp.canFallThrough(), widenedOp.isTerminal(),
                new int[0], widenedIndex);
    }

    /** Get the implicit variable index for a switch (not applicable — returns -1). */
    private int varIndex(Opcode op) {
        return op.implicitVarIndex();
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
