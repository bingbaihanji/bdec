package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.opcode.Opcode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM 字节码指令解码器.
 *
 * <p>从数据输入流中逐条解码 JVM 字节码指令,生成 {@link Instruction} 模型对象.
 * 支持所有标准指令格式,包括:
 * <ul>
 *   <li>无操作数指令(如 {@code iconst_0},{@code return})</li>
 *   <li>单字节操作数指令(如 {@code bipush},{@code iload})</li>
 *   <li>双字节操作数指令(如 {@code sipush},{@code goto},字段/方法调用指令)</li>
 *   <li>变长指令({@code tableswitch},{@code lookupswitch})</li>
 *   <li>扩展指令({@code wide})</li>
 *   <li>四字节操作数指令(如 {@code invokeinterface},{@code invokedynamic},{@code goto_w})</li>
 *   <li>三字节操作数指令(如 {@code multianewarray})</li>
 * </ul>
 */
public final class InstructionDecoder {

    /**
     * 当前流位置解码一条字节码指令.
     *
     * <p>读取一个操作码字节,查找对应的 {@link Opcode} 枚举值,
     * 然后根据操作数的字节数读取并解码操作数,构造 {@link Instruction} 对象.
     *
     * @param in     指向指令起始位置的数据输入流
     * @param offset 当前指令在方法字节码中的偏移量
     * @return 解码后的指令对象,若遇到无法识别的操作码则返回 {@code null}
     * @throws IOException 如果读取数据流时发生 I/O 错误
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

        // tableswitch/lookupswitch 为变长指令,需特殊处理
        if (op == Opcode.TABLESWITCH || op == Opcode.LOOKUPSWITCH) {
            return decodeSwitch(in, offset, op);
        }

        // WIDE 指令扩展后续指令的局部变量索引为双字节
        // WIDE + IINC:双字节索引 + 双字节有符号常量
        // WIDE + ILOAD/FLOAD/ALOAD/LLOAD/DLOAD/ISTORE/FSTORE/ASTORE/LSTORE/DSTORE/RET:双字节索引
        if (op == Opcode.WIDE) {
            return decodeWide(in, offset);
        }

        // IINC 需要特殊处理:两个单独的无符号字节(索引 + 常量),不是一个 u2
        if (op == Opcode.IINC) {
            int index = in.readUnsignedByte();
            int incr = in.readByte(); // 有符号增量
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
                // 当操作数字节携带局部变量索引时更新 varIndex
                // 显式索引操作码(ILOAD,ISTORE 等)的 implicitVarIndex==0 但 operandCount==1,
                // 此时操作数即为索引.
                // 隐式索引操作码(ILOAD_0,ISTORE_3 等)的 operandCount==0,
                // 索引来自 implicitVarIndex.
                // 非索引操作码(BIPUSH,LDC 等)的 implicitVarIndex==-1.
                if (op.implicitVarIndex() == 0) {
                    varIndex = val;
                }
            }
            case 2 -> {
                int val = in.readUnsignedShort();
                operands.add(val);
                if (op.implicitVarIndex() == 0) {
                    varIndex = val;
                }
                // 分支指令:有符号的 16 位偏移量,从指令起始位置计算
                if (op.isConditional() || op == Opcode.GOTO || op == Opcode.JSR) {
                    short branchOffset = (short) val;
                    jumpTargets = new int[]{offset + branchOffset};
                }
            }
            case 4 -> {
                // INVOKEINTERFACE:2 字节索引 + 1 字节计数 + 1 字节 0
                // INVOKEDYNAMIC:2 字节索引 + 2 字节 0(必须为零)
                // GOTO_W/JSR_W:4 字节有符号分支偏移量
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
                    operands.add(index); // 仅常量池索引有意义
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
                // MULTIANEWARRAY:2 字节常量池索引 + 1 字节维数
                int index = in.readUnsignedShort();
                int dims = in.readUnsignedByte();
                operands.add(index);
                operands.add(dims);
            }
            case 0 -> {
                // 无操作数,不需要特殊处理
            }
            default -> {
                // 正常情况下不应该到达这里
            }
        }

        return new Instruction(offset, opcodeByte, op.mnemonic(),
                operands, op.canFallThrough(), op.isTerminal(), jumpTargets, varIndex);
    }

    /**
     * 解码 {@code tableswitch} 或 {@code lookupswitch} 指令.
     *
     * <p>这两种 switch 指令为变长格式,结构如下:
     * <ul>
     *   <li>{@code tableswitch}:操作码 + 0-3 字节填充 + default + low + high + (high-low+1) 个跳转偏移</li>
     *   <li>{@code lookupswitch}:操作码 + 0-3 字节填充 + default + npairs + npairs 对 (match, offset)</li>
     * </ul>
     *
     * @param in     数据输入流
     * @param offset 指令起始偏移量
     * @param op     操作码枚举值
     * @return 解码后的 switch 指令对象
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    private Instruction decodeSwitch(DataInputStream in, int offset, Opcode op) throws IOException {
        // 在操作码之后跳过 0-3 个填充字节以达到相对于方法起始的 4 字节对齐
        int alignedOffset = (offset + 4) & ~3;
        int skipBytes = alignedOffset - offset - 1; // -1 因为已经读取了操作码字节
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
            // LOOKUPSWITCH 格式:default + npairs + (match, offset) 对
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

    /**
     * 解码 {@code wide} 前缀指令.
     *
     * <p>{@code wide} 将其后一条指令的局部变量索引从单字节扩展为双字节.
     * 特殊格式:{@code wide iinc} 需要读取双字节索引和双字节有符号增量.
     *
     * @param in     数据输入流
     * @param offset 指令起始偏移量
     * @return 解码后的扩展指令对象(操作码和助记符均使用被扩展的指令)
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    private Instruction decodeWide(DataInputStream in, int offset) throws IOException {
        int widenedOpcode = in.readUnsignedByte();
        Opcode widenedOp;
        try {
            widenedOp = Opcode.byCode(widenedOpcode);
        } catch (IllegalArgumentException e) {
            System.err.println("WARNING: unknown widened opcode " + widenedOpcode + " at offset " + offset);
            return null;
        }

        int widenedIndex = in.readUnsignedShort(); // 双字节索引
        List<Integer> operands = new ArrayList<>();
        operands.add(widenedIndex);

        // WIDE + IINC:双字节索引 + 双字节有符号常量
        if (widenedOp == Opcode.IINC) {
            int incr = in.readShort(); // 有符号增量
            operands.add(incr);
        }

        // 使用被扩展的操作码(而非 196),以便 IrBuilder 正确分发.
        // 助记符保留 "wide" 前缀以便调试.
        return new Instruction(offset, widenedOpcode, "wide " + widenedOp.mnemonic(),
                operands, widenedOp.canFallThrough(), widenedOp.isTerminal(),
                new int[0], widenedIndex);
    }

    /**
     * 获取 switch 指令的隐式变量索引(不适用,返回 -1).
     *
     * @param op 操作码枚举值
     * @return 隐式变量索引值
     */
    private int varIndex(Opcode op) {
        return op.implicitVarIndex();
    }

    /**
     * 从字节数组范围中解码所有指令.
     *
     * <p>这是解码方法体 {@code Code} 属性中所有指令的批量入口方法.
     *
     * @param code    包含字节码的完整字节数组
     * @param startPc 起始程序计数器(通常为 0)
     * @param length  代码长度
     * @return 解码后的指令列表
     * @throws IOException 如果读取过程中发生 I/O 错误
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
