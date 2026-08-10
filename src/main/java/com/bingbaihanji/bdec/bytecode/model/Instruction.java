package com.bingbaihanji.bdec.bytecode.model;

import java.util.List;

/**
 * 字节码指令模型.
 *
 * <p>表示 JVM 方法体中的一条字节码指令,包含其偏移量,操作码,助记符,
 * 原始操作数,控制流信息(是否可穿透,是否终止指令),跳转目标以及
 * 关联的局部变量索引.
 *
 * <p>该 record 在构造时自动将 {@code null} 的跳转目标数组规范化为空数组.
 *
 * @param offset        指令在方法字节码中的起始偏移量
 * @param opcode        操作码数值(0-255)
 * @param mnemonic      操作码助记符字符串(如 {@code "iload"},{@code "invokevirtual"})
 * @param rawOperands   原始操作数列表(已解码为 Java 整型值)
 * @param canFallThrough 该指令执行后控制流是否可以穿透到下一条指令
 * @param isTerminal    该指令是否为方法的终止指令(如 {@code return},{@code athrow})
 * @param jumpTargets   可能的跳转目标偏移量数组(条件/无条件跳转,switch 指令)
 * @param varIndex      指令隐式或显式操作的局部变量索引,若不涉及则为 -1
 */
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

    /**
     * 紧凑构造函数:确保 jumpTargets 不为 null.
     */
    public Instruction {
        if (jumpTargets == null) {
            jumpTargets = new int[0];
        }
    }
}
