package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.Instruction;

import java.util.List;

/**
 * 基本块.
 * <p>
 * 控制流图中的基本块是单入口单出口的线性指令序列.
 * 每个基本块由一段连续的字节码指令组成,是构建控制流图和进行数据流分析的基本单元.
 * </p>
 */
public final class BasicBlock {

    /** 基本块唯一标识符 */
    private final int id;

    /** 起始字节码偏移量 */
    private final int startOffset;

    /** 结束字节码偏移量 */
    private final int endOffset;

    /** 基本块内的指令列表(不可变) */
    private final List<Instruction> instructions;

    /**
     * 构造一个基本块.
     *
     * @param id           基本块ID
     * @param instructions 该块包含的指令列表
     */
    public BasicBlock(int id, List<Instruction> instructions) {
        this.id = id;
        this.instructions = List.copyOf(instructions);
        this.startOffset = instructions.isEmpty() ? 0 : instructions.get(0).offset();
        this.endOffset = instructions.isEmpty() ? 0
                : instructions.get(instructions.size() - 1).offset();
    }

    /** @return 基本块ID */
    public int id() {return id;}

    /** @return 起始字节码偏移量 */
    public int startOffset() {return startOffset;}

    /** @return 结束字节码偏移量 */
    public int endOffset() {return endOffset;}

    /** @return 基本块内不可变的指令列表 */
    public List<Instruction> instructions() {return instructions;}

    /**
     * 获取基本块的第一条指令.
     *
     * @return 第一条指令,若块为空则返回 {@code null}
     */
    public Instruction firstInstruction() {
        return instructions.isEmpty() ? null : instructions.get(0);
    }

    /**
     * 获取基本块的最后一条指令.
     *
     * @return 最后一条指令,若块为空则返回 {@code null}
     */
    public Instruction lastInstruction() {
        return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1);
    }

    /**
     * 判断基本块是否以无条件跳转指令结尾.
     * 即以goto,return,athrow等不会自然落入下一条指令的指令结尾.
     *
     * @return 如果以无条件跳转结尾则返回 {@code true}
     */
    public boolean endsWithUnconditionalJump() {
        var last = lastInstruction();
        return last != null && last.isTerminal() && !last.canFallThrough();
    }

    /**
     * 判断基本块是否以条件跳转指令结尾.
     * 即以 if 系列指令结尾.
     *
     * @return 如果以条件跳转结尾则返回 {@code true}
     */
    public boolean endsWithConditionalJump() {
        var last = lastInstruction();
        if (last == null) {
            return false;
        }
        return last.mnemonic().startsWith("if") && !last.isTerminal();
    }

    /**
     * 判断基本块是否以switch指令结尾.
     *
     * @return 如果以tableswitch(170)或lookupswitch(171)结尾则返回 {@code true}
     */
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
