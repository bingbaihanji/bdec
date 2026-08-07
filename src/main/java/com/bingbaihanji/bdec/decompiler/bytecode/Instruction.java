package com.bingbaihanji.bdec.decompiler.bytecode;

import java.util.List;

public interface Instruction {

    int offset();

    int opcode();

    String mnemonic();

    List<InstructionOperand> operands();

    boolean canFallThrough();

    boolean isTerminal();
}
