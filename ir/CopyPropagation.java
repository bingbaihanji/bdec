package com.bingbaihanji.bdec.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 复制传播优化器.
 * <p>
 * 将仅被赋值一次且赋值为简单常量或另一变量的变量引用替换为原始值,
 * 以减少中间变量的使用.采用两遍扫描:第一遍找出可传播的定义,
 * 第二遍替换LOAD指令中的变量引用.
 * </p>
 */
public final class CopyPropagation {

    /**
     * 对IR指令列表执行复制传播优化.
     *
     * @param instructions 原始IR指令列表
     * @return 复制传播后的新指令列表
     */
    public List<IrInstruction> propagate(List<IrInstruction> instructions) {
        // 变量槽位 → 定义值的映射(仅当变量被唯一定义且值为简单类型时有效)
        Map<Integer, Value> copyMap = new HashMap<>();
        // 变量槽位 → 定义指令的映射(用于检测重复定义)
        Map<Integer, IrInstruction> defInsn = new HashMap<>();

        // 第一遍:找出只被定义一次的变量
        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2) {
                Value target = insn.operands().get(0);
                Value source = insn.operands().get(1);
                if (target instanceof Variable v) {
                    int slot = v.slot();
                    if (defInsn.containsKey(slot)) {
                        // 多次定义 → 不能安全传播
                        copyMap.remove(slot);
                    } else if (isPropagable(source)) {
                        copyMap.put(slot, source);
                        defInsn.put(slot, insn);
                    }
                }
            }
        }

        if (copyMap.isEmpty()) {
            return instructions;
        }

        // 第二遍:将LOAD引用替换为传播后的值
        List<IrInstruction> result = new ArrayList<>();
        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.LOAD && insn.operands().size() == 1
                    && insn.operands().getFirst() instanceof Variable v) {
                Value replacement = copyMap.get(v.slot());
                if (replacement != null) {
                    // 将LOAD指令替换为源值,更新操作数
                    IrInstruction replaced = new IrInstruction(insn.id(), insn.opcode(),
                            insn.resultType(), List.of(replacement), insn.sourceOffset(), insn.blockId());
                    replaced.setResultValue(new InstructionRef(replaced, insn.resultType()));
                    result.add(replaced);
                    continue;
                }
            }
            result.add(insn);
        }

        return result;
    }

    /**
     * 检查一个值是否可以安全地传播(值必须是常量或另一变量).
     *
     * @param v 待检查的值
     * @return 如果可以传播则返回 {@code true}
     */
    private boolean isPropagable(Value v) {
        return v instanceof ConstantValue || v instanceof Variable;
    }
}
