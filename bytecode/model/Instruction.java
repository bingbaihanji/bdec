package com.bingbaihanji.bdec.bytecode.model;

import java.util.List;

public record Instruction(
        int offset,
        int opcode,
        String mnemonic,
        List<Integer> rawOperands,
        boolean canFallThrough,
        boolean isTerminal,
        int[] jumpTargets,
        int varIndex
) {

    public Instruction {
        if (jumpTargets == null) {
            jumpTargets = new int[0];
        }
    }
}
