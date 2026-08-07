package com.bingbaihanji.bdec.decompiler.ir;

import java.util.List;

public interface IrInstruction {

    IrOpcode opcode();

    List<Value> operands();

    Value result();

    int sourceBytecodeOffset();
}
