package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.Instruction;

import java.util.List;

public final class BasicBlock {

    private final int id;

    private final int startOffset;

    private final int endOffset;

    private final List<Instruction> instructions;

    public BasicBlock(int id, List<Instruction> instructions) {
        this.id = id;
        this.instructions = List.copyOf(instructions);
        this.startOffset = instructions.isEmpty() ? 0 : instructions.get(0).offset();
        this.endOffset = instructions.isEmpty() ? 0
                : instructions.get(instructions.size() - 1).offset();
    }

    public int id() {return id;}

    public int startOffset() {return startOffset;}

    public int endOffset() {return endOffset;}

    public List<Instruction> instructions() {return instructions;}

    public Instruction firstInstruction() {
        return instructions.isEmpty() ? null : instructions.get(0);
    }

    public Instruction lastInstruction() {
        return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1);
    }

    public boolean endsWithUnconditionalJump() {
        var last = lastInstruction();
        return last != null && last.isTerminal() && !last.canFallThrough();
    }

    public boolean endsWithConditionalJump() {
        var last = lastInstruction();
        if (last == null) {
            return false;
        }
        return last.mnemonic().startsWith("if") && !last.isTerminal();
    }

    public boolean endsWithSwitch() {
        var last = lastInstruction();
        return last != null && (last.opcode() == 170 || last.opcode() == 171);
    }

    @Override
    public String toString() {
        return "B" + id + " [" + startOffset + "-" + endOffset + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BasicBlock that)) {
            return false;
        }
        return id == that.id;
    }

    @Override
    public int hashCode() {return Integer.hashCode(id);}
}
