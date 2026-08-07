package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InstructionDecoderTest {

    @Test
    public void testDecodeIconst0() throws IOException {
        // iconst_0 = opcode 3, no operands
        byte[] code = {0x03};
        InstructionDecoder decoder = new InstructionDecoder();
        Instruction insn = decoder.decode(new DataInputStream(
                new ByteArrayInputStream(code)), 0);

        assertEquals(0, insn.offset());
        assertEquals(3, insn.opcode());
        assertEquals("iconst_0", insn.mnemonic());
        assertTrue(insn.rawOperands().isEmpty());
        assertTrue(insn.canFallThrough());
        assertFalse(insn.isTerminal());
        assertEquals(0, insn.jumpTargets().length);
    }

    @Test
    public void testDecodeIload1() throws IOException {
        // iload_1 = opcode 27, no operands, implicit varIndex=1
        byte[] code = {0x1B};
        InstructionDecoder decoder = new InstructionDecoder();
        Instruction insn = decoder.decode(new DataInputStream(
                new ByteArrayInputStream(code)), 0);

        assertEquals("iload_1", insn.mnemonic());
        assertEquals(1, insn.varIndex());
    }

    @Test
    public void testDecodeGoto() throws IOException {
        // goto 0x0006 = opcode 167, operand 0x0006
        // jump target = 0 (offset) + 6 = 6
        byte[] code = {(byte) 0xA7, 0x00, 0x06};
        InstructionDecoder decoder = new InstructionDecoder();
        Instruction insn = decoder.decode(new DataInputStream(
                new ByteArrayInputStream(code)), 0);

        assertEquals("goto", insn.mnemonic());
        assertArrayEquals(new int[]{6}, insn.jumpTargets());
        assertFalse(insn.canFallThrough());
        assertFalse(insn.isTerminal()); // goto is not terminal — it redirects, doesn't end method
    }

    @Test
    public void testDecodeIfeq() throws IOException {
        // ifeq 0x0005 = opcode 153, branch to offset+5
        byte[] code = {(byte) 0x99, 0x00, 0x05};
        InstructionDecoder decoder = new InstructionDecoder();
        Instruction insn = decoder.decode(new DataInputStream(
                new ByteArrayInputStream(code)), 0);

        assertEquals("ifeq", insn.mnemonic());
        assertArrayEquals(new int[]{5}, insn.jumpTargets());
        assertFalse(insn.canFallThrough());
        assertFalse(insn.isTerminal()); // ifeq is conditional, not terminal
    }

    @Test
    public void testDecodeReturn() throws IOException {
        byte[] code = {(byte) 0xB1}; // return
        InstructionDecoder decoder = new InstructionDecoder();
        Instruction insn = decoder.decode(new DataInputStream(
                new ByteArrayInputStream(code)), 0);

        assertEquals("return", insn.mnemonic());
        assertFalse(insn.canFallThrough());
        assertTrue(insn.isTerminal());
    }

    @Test
    public void testDecodeAll() throws IOException {
        // Sequence: iconst_0, iconst_1, iadd, ireturn
        byte[] code = {0x03, 0x04, 0x60, (byte) 0xAC};
        InstructionDecoder decoder = new InstructionDecoder();
        List<Instruction> insns = decoder.decodeAll(code, 0, code.length);

        assertEquals(4, insns.size());
        assertEquals("iconst_0", insns.get(0).mnemonic());
        assertEquals("iconst_1", insns.get(1).mnemonic());
        assertEquals("iadd", insns.get(2).mnemonic());
        assertEquals("ireturn", insns.get(3).mnemonic());
    }
}
