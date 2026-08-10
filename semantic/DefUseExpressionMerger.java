package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义-使用表达式合并器.
 *
 * <p>基于简单的定值-使用(def-use)分析,跨基本块合并单次使用的中间表达式.
 *
 * <p>对于每个恰好被使用一次的 STORE 指令定义的值:
 * <ul>
 *   <li>将存储的值内联到使用点</li>
 *   <li>移除该 STORE 指令</li>
 *   <li>如果定义指令也是单次使用,则递归内联</li>
 * </ul>
 *
 * <p>设计参考了 CFR 的 {@code LValueProp}(副本传播)和 Procyon 的
 * {@code CopyPropagation} 遍历.
 */
public final class DefUseExpressionMerger {

    /**
     * 合并单次使用的表达式.
     *
     * @param ir 待处理的线性 IR
     * @return 如果进行了任何合并则返回 true
     */
    public boolean merge(LinearIr ir) {
        boolean changed = false;
        List<IrInstruction> instructions = new ArrayList<>(ir.instructions());

        // 构建 def-use 计数映射:记录每条指令结果被引用的次数
        Map<Integer, Integer> useCount = new HashMap<>();
        // 构建使用点映射:defId → [使用该定义的指令 ID 列表]
        Map<Integer, List<Integer>> useSites = new HashMap<>();

        for (IrInstruction insn : instructions) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    int defId = ref.instruction().id();
                    useCount.merge(defId, 1, Integer::sum);
                    useSites.computeIfAbsent(defId, k -> new ArrayList<>()).add(insn.id());
                }
            }
        }

        // 寻找恰好被使用一次的 STORE 指令,标记内联
        Set<Integer> toRemove = new HashSet<>();
        // defId → 替换值映射
        Map<Integer, Value> replacements = new HashMap<>();

        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.STORE) {
                int useCnt = useCount.getOrDefault(insn.id(), 0);
                if (useCnt == 1 && insn.operands().size() >= 2) {
                    // STORE 操作数结构:[目标变量, 源值]
                    Value source = insn.operands().get(1);
                    toRemove.add(insn.id());
                    replacements.put(insn.id(), source);
                    insn.addAnnotation(SemanticAnnotation.of(SemanticTag.SINGLE_USE_INLINE));
                    changed = true;
                }
            }

            // 同时内联简单的 LOAD+单次使用模式:
            // 如果指令产生的值恰好被使用一次,且定义指令是简单表达式
            //(如 BINARY,CAST 等),则标记为内联候选.
            if (isSimpleExpr(insn)) {
                int useCnt = useCount.getOrDefault(insn.id(), 0);
                if (useCnt == 1) {
                    insn.addAnnotation(SemanticAnnotation.of(SemanticTag.SINGLE_USE_INLINE));
                }
            }
        }

        // 移除已标记的 STORE 指令,并替换操作数引用
        if (changed) {
            List<IrInstruction> filtered = new ArrayList<>();
            for (IrInstruction insn : instructions) {
                if (toRemove.contains(insn.id())) {
                    continue;
                }
                // 若操作数引用了已替换的定义,则进行替换
                filtered.add(substituteOperands(insn, replacements));
            }
            ir.replaceInstructions(filtered);
        }

        return changed;
    }

    /**
     * 判断指令是否为简单表达式(无副作用的运算).
     *
     * @param insn 待检查的 IR 指令
     * @return 若指令为简单表达式则返回 true
     */
    private boolean isSimpleExpr(IrInstruction insn) {
        return switch (insn.opcode()) {
            case BINARY, UNARY, CAST, FIELD_LOAD, ARRAY_LOAD,
                 ARRAY_LENGTH, INSTANCE_OF, CONST -> true;
            default -> false;
        };
    }

    /**
     * 将指令的操作数引用替换为内联后的值.
     *
     * @param insn         原始指令
     * @param replacements defId → 替换值映射
     * @return 操作数替换后的新指令(若无替换则返回原指令)
     */
    private IrInstruction substituteOperands(IrInstruction insn,
                                             Map<Integer, Value> replacements) {
        boolean changed = false;
        List<Value> newOps = new ArrayList<>();
        for (Value op : insn.operands()) {
            if (op instanceof InstructionRef ref
                    && replacements.containsKey(ref.instruction().id())) {
                newOps.add(replacements.get(ref.instruction().id()));
                changed = true;
            } else {
                newOps.add(op);
            }
        }
        if (!changed) {
            return insn;
        }

        return new IrInstruction(insn.id(), insn.opcode(), insn.resultType(),
                newOps, insn.sourceOffset(), insn.blockId(),
                insn.originalOpcode(), insn.nameHint());
    }
}
