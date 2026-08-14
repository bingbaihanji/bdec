package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变量内联预遍历——从 {@link BlockReducer} 中提取的跨组 STORE→使用点
 * 内联映射构建逻辑(里程碑 Phase 3).
 *
 * <p>扫描所有组找出恰好被使用一次的变量的 STORE→Variable→LOAD 链,
 * 跨组边界工作(对 try-finally 模式至关重要:STORE 位于 try 体组,
 * 而 LOAD+RETURN 位于正常退出组).所有方法均为无状态静态方法.</p>
 */
final class InlineAnalyzer {

    private InlineAnalyzer() {}

    /**
     * 为单次使用的变量构建全局(跨组)的 Variable → 存储值的内联映射.
     * 这使得常量可以跨组边界内联,例如 STORE 位于 try 体组中而
     * LOAD+RETURN 位于正常退出组中(常见于 try-finally 模式).
     */
    static InlineAnalysis analyze(List<BlockGroup> groups, LinearIr ir) {
        Map<Variable, Value> varStoreSource = new HashMap<>();
        Set<Integer> storesToSkip = new HashSet<>();

        // 跨所有组收集全部指令
        List<IrInstruction> allInsns = new ArrayList<>();
        for (BlockGroup g : groups) {
            allInsns.addAll(g.allIrInstructions(ir));
        }

        // 第一遍:统计每个 Variable 被引用的次数
        //(同时通过 LOAD 和其他指令操作数如 RETURN 进行统计).
        Map<Variable, Integer> varUseCount = new HashMap<>();
        Map<Variable, Integer> loadIdForVar = new HashMap<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.LOAD && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                varUseCount.merge(v, 1, Integer::sum);
                loadIdForVar.put(v, insn.id());
            }
            // 同时统计直接的 Variable 引用(例如 RETURN 操作数).
            // INC 排除在外:其操作数是旧版本的读取与新版本的定义,
            // 把旧版本算作"使用"会把 STORE 声明内联掉(如 int i = 0; i++ 的
            // i(v1) 仅被 INC 引用,内联后声明丢失且无法还原为 0++).
            for (Value op : insn.operands()) {
                if (op instanceof Variable v && insn.opcode() != IrOpcode.STORE
                        && insn.opcode() != IrOpcode.LOAD
                        && insn.opcode() != IrOpcode.INC) {
                    varUseCount.merge(v, 1, Integer::sum);
                }
            }
        }

        // 构建已消费集合(InstructionRef 使用 + 直接 Variable 使用)
        Set<Integer> consumedInsnIds = new HashSet<>();
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumedInsnIds.add(ref.instruction().id());
                }
            }
        }

        // 第二遍:追踪单次使用变量的存储
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                    && insn.operands().get(0) instanceof Variable v) {
                // 带 JSR-308 类型注解的变量不可内联——注解会随声明一起丢失
                if (v.typeAnnotations() != null && !v.typeAnnotations().isEmpty()) {
                    continue;
                }
                Value source = insn.operands().get(1);
                int useCount = varUseCount.getOrDefault(v, 0);
                if (useCount == 1 && StatementUtils.isSimpleValue(source)) {
                    // 检查 LOAD 是否被消费,或变量是否被直接使用(如 RETURN 操作数)
                    Integer loadId = loadIdForVar.get(v);
                    boolean canInline;
                    if (loadId != null) {
                        // 变量通过 LOAD 加载——检查 LOAD 是否被消费
                        canInline = consumedInsnIds.contains(loadId);
                    } else {
                        // 变量被直接使用——始终安全内联(变量自身即为使用点)
                        canInline = true;
                    }
                    if (canInline) {
                        // 合并点语义保护:仅当 STORE 支配其唯一使用点才可内联.
                        // 多前驱汇合块(如 switch 的 case 汇合)中的 LOAD 读的是
                        // 路径相关值,内联会把单路径常量错误传播
                        // (如把 switch (var3) 变成 switch (0)).
                        if (!storeDominatesLoad(insn, v, loadId, ir)) {
                            continue;
                        }
                        varStoreSource.put(v, source);
                        storesToSkip.add(insn.id());
                    }
                }
            }
        }

        return new InlineAnalysis(Map.copyOf(varStoreSource),
                Set.copyOf(storesToSkip), Map.copyOf(varUseCount));
    }

    /**
     * STORE→使用点内联的支配检查:使用点所在块必须被 STORE 块支配.
     *
     * <p>多前驱汇合块(如 switch 的 case 汇合块)中的 LOAD 读的是
     * 路径相关值,把单一路径的 STORE 常量内联进去会把错误值传播为
     * 判别式(如两级字符串 switch 的临时变量被内联成 switch (0)).
     * 非支配时返回 false 禁止内联;无 CFG 信息或解析失败时保守允许
     * (保持旧行为).</p>
     */
    static boolean storeDominatesLoad(IrInstruction storeInsn, Variable v,
                                      Integer loadId, LinearIr ir) {
        try {
            ControlFlowGraph cfg = ir.controlFlowGraph();
            if (cfg == null) {
                return true;
            }
            DominatorTree dom = cfg.dominatorTree();
            BasicBlock storeBlock = null;
            for (BasicBlock b : cfg.blocks()) {
                if (b.id() == storeInsn.blockId()) {
                    storeBlock = b;
                    break;
                }
            }
            if (storeBlock == null) {
                return true;
            }
            // 定位唯一使用点所在块:按对象身份或 slot+version 等价匹配
            // (LOAD 的 Variable 可能与 STORE 的目标是不同实例).
            // 排除 STORE 本身与其他 STORE——它们的目标操作数会误匹配.
            int useBlockId = -1;
            for (IrInstruction i : ir.instructions()) {
                if (i == storeInsn || i.opcode() == IrOpcode.STORE) {
                    continue;
                }
                for (Value op : i.operands()) {
                    if (op == v || (op instanceof Variable ov
                            && ov.slot() == v.slot() && ov.version() == v.version())) {
                        useBlockId = i.blockId();
                        break;
                    }
                }
                if (useBlockId >= 0) {
                    break;
                }
            }
            if (useBlockId < 0) {
                return true;
            }
            BasicBlock useBlock = null;
            for (BasicBlock b : cfg.blocks()) {
                if (b.id() == useBlockId) {
                    useBlock = b;
                    break;
                }
            }
            return useBlock == null || dom.dominates(storeBlock, useBlock);
        } catch (Exception e) {
            return true;
        }
    }

    /** 分析结果:单次使用变量的 STORE→存储值内联映射与需跳过的 STORE ID. */
    record InlineAnalysis(Map<Variable, Value> varStoreSource,
                          Set<Integer> storesToSkip,
                          Map<Variable, Integer> varUseCount) {}
}
