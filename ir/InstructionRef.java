package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

public record InstructionRef(IrInstruction instruction, JavaType type) implements Value {
}
