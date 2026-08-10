package com.bingbaihanji.bdec.ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 死代码消除器.
 * <p>
 * 移除结果从未被使用的指令.采用标记-清除(mark-and-sweep)策略:
 * 从"活跃根"指令(返回,存储,抛出,有副作用的方法调用等)开始,
 * 标记所有传递依赖的指令为活跃,然后清除未被标记的指令.
 * </p>
 */
public final class DeadCodeElimination {

    /**
     * 执行死代码消除.
     *
     * @param instructions 原始IR指令列表
     * @return 移除死代码后的新指令列表
     */
    public List<IrInstruction> eliminate(List<IrInstruction> instructions) {
        // 第一步:标记 —— 从活跃根开始找到所有传递使用的指令
        Set<Integer> live = new HashSet<>();
        Deque<IrInstruction> worklist = new ArrayDeque<>();

        // 种子:具有副作用的指令或产生可观察结果的指令
        for (IrInstruction insn : instructions) {
            if (isLiveRoot(insn)) {
                live.add(insn.id());
                worklist.add(insn);
            }
        }

        // 将活跃指令的所有操作数标记为活跃(传递闭包)
        while (!worklist.isEmpty()) {
            IrInstruction insn = worklist.poll();
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    IrInstruction def = ref.instruction();
                    if (live.add(def.id())) {
                        worklist.add(def);
                    }
                }
            }
        }

        // 第二步:清除 —— 只保留被标记为活跃的指令
        List<IrInstruction> result = new ArrayList<>();
        for (IrInstruction insn : instructions) {
            if (live.contains(insn.id())) {
                result.add(insn);
            }
        }

        return result;
    }

    /**
     * 判断一条指令是否为"活跃根"——即必须保留的指令.
     * 活跃根包括:返回,抛出,存储指令,方法调用(可能有副作用),
     * 监视器操作,条件分支和switch指令.
     *
     * @param insn IR指令
     * @return 如果是活跃根则返回 {@code true}
     */
    private boolean isLiveRoot(IrInstruction insn) {
        return switch (insn.opcode()) {
            case RETURN, THROW, STORE, FIELD_STORE, ARRAY_STORE -> true;
            case INVOKE -> true; // 方法调用可能有副作用
            case MONITOR_ENTER, MONITOR_EXIT -> true;
            case CONDITION -> true; // 控制分支结构
            case SWITCH -> true;
            default -> false;
        };
    }
}
