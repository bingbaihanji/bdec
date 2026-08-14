package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.semantic.SemanticTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 构造器合并预遍历——从 {@link BlockReducer} 中提取的跨组 NEW + INVOKE
 * {@code <init>} 对合并逻辑(里程碑 Phase 3).
 *
 * <p>对标 CFR 的 CondenseConstruction 和 Vineflower 的
 * {@code SimplifyExprentsHelper.isSimpleConstructorInvocation()}.
 * 当 NEW 指令和对应的 CONSTRUCTOR_DELEGATION INVOKE 被拆分到
 * 不同的 BlockGroup 中时(例如记录,sealed 类构造),执行合并.</p>
 */
final class ConstructionMerger {

    private ConstructionMerger() {}

    /** 合并结果:NEW 指令 ID → 对应 {@code <init>} 调用列表,及需跳过的 INVOKE ID. */
    record MergeResult(Map<Integer, List<IrInstruction>> newToInit,
                       Set<Integer> initToSkip) {}

    /**
     * 全局预遍历:合并跨组的 NEW + INVOKE {@code <init>} 对.
     *
     * <p>如果仅做组内合并,会产生:
     * <pre>{@code
     *   RecordDemo("Alice", 25);  // 孤立的构造函数调用
     *   RecordDemo r = new RecordDemo(); // 无参 new
     * }</pre>
     * 而不是正确的:
     * <pre>{@code RecordDemo r = new RecordDemo("Alice", 25);}</pre>
     */
    static MergeResult merge(List<BlockGroup> groups, LinearIr ir) {
        // 收集所有组中的所有指令,并计算跨组 consumed 集合
        Set<Integer> allConsumed = new HashSet<>();
        List<IrInstruction> allInsns = new ArrayList<>();
        for (BlockGroup group : groups) {
            List<IrInstruction> groupInsns = group.allIrInstructions(ir);
            allInsns.addAll(groupInsns);
            for (IrInstruction insn : groupInsns) {
                for (Value op : insn.operands()) {
                    if (op instanceof InstructionRef ref) {
                        allConsumed.add(ref.instruction().id());
                    }
                }
            }
        }

        // 合并所有 CONSTRUCTOR_DELEGATION INVOKE(非 this/super)到其对应的 NEW 指令中
        Map<Integer, List<IrInstruction>> newToInit = new HashMap<>();
        Set<Integer> initToSkip = new HashSet<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.INVOKE
                    && insn.hasTag(SemanticTag.CONSTRUCTOR_DELEGATION)
                    && !insn.hasTag(SemanticTag.THIS_CONSTRUCTOR)
                    && !insn.hasTag(SemanticTag.SUPER_CONSTRUCTOR)) {
                for (Value op : insn.operands()) {
                    if (op instanceof InstructionRef ref) {
                        IrInstruction def = ref.instruction();
                        if (def.opcode() == IrOpcode.NEW && allConsumed.contains(def.id())) {
                            newToInit.computeIfAbsent(def.id(), k -> new ArrayList<>()).add(insn);
                            initToSkip.add(insn.id());
                            break;
                        }
                    }
                }
            }
        }
        return new MergeResult(newToInit, initToSkip);
    }
}
