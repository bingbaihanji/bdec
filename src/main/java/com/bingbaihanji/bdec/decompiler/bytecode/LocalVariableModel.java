package com.bingbaihanji.bdec.decompiler.bytecode;

public record LocalVariableModel(
        int slot,
        String name,
        String descriptor,
        String signature,
        int startOffset,
        int length
) {
}
