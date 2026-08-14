package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.ExceptionHandlerModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 控制流图构建器.
 * <p>
 * 负责将方法的字节码指令列表转换为控制流图.
 * 构建过程包括:识别基本块边界(leader识别),划分基本块,
 * 创建入口/出口块,构建控制流边(顺序流,分支,跳转,异常边等).
 * </p>
 */
public final class CfgBuilder {

    /**
     * 计算基本块的结束偏移量,即下一个块起始偏移或方法末尾之后的位置.
     *
     * @param b          当前基本块
     * @param blocks     所有基本块列表
     * @param lastOffset 方法最后一条指令偏移之后的位置
     * @return 基本块的结束偏移量
     */
    private static int getBlockEndOffset(BasicBlock b, List<BasicBlock> blocks, int lastOffset) {
        for (BasicBlock next : blocks) {
            if (next.startOffset() > b.startOffset()) {
                return next.startOffset();
            }
        }
        return lastOffset;
    }

    /**
     * 为指定方法构建控制流图.
     *
     * <h3>构建流程</h3>
     * <ol>
     *   <li>识别基本块入口点(leaders)——包括跳转目标,条件指令的后续指令,
     *       异常处理器入口,try范围边界等.</li>
     *   <li>按偏移量排序并划分基本块.</li>
     *   <li>创建虚拟的入口块和出口块.</li>
     *   <li>构建控制流边:顺序边,分支边,跳转边,switch边,异常边.</li>
     * </ol>
     *
     * @param method 方法模型
     * @return 构建完成的控制流图
     */
    public ControlFlowGraph build(MethodModel method) {
        List<Instruction> instructions = method.instructions();
        if (instructions == null || instructions.isEmpty()) {
            BasicBlock entry = new BasicBlock(0, List.of());
            BasicBlock exit = new BasicBlock(1, List.of());
            return new ControlFlowGraph(method, entry, exit,
                    List.of(entry, exit), List.of(), List.of());
        }

        // 第一步:识别基本块入口点(leaders)
        // 包括跳转目标和不可落入指令的下一条指令
        Set<Integer> leaders = new LinkedHashSet<>();
        leaders.add(instructions.get(0).offset());

        for (int i = 0; i < instructions.size(); i++) {
            Instruction insn = instructions.get(i);
            for (int target : insn.jumpTargets()) {
                leaders.add(target);
            }
            // 对于不可落入的指令(如goto,if*,tableswitch,lookupswitch,athrow),
            // 其后继指令只能通过显式跳转到达,因此必须成为leader
            if (!insn.canFallThrough() && i + 1 < instructions.size()) {
                leaders.add(instructions.get(i + 1).offset());
            }
        }

        // 异常处理器入口必须成为leader
        // 同时将try范围的边界(startPc和endPc)也标记为leader,
        // 以防止基本块跨越try边界.这对于try-finally至关重要:
        //   - try之前的代码(如lock.lock())必须与try体分离
        //   - try之后的正常退出代码(如unlock; return)必须在独立块中(不含EXCEPTION边)
        if (method.exceptionHandlers() != null) {
            for (ExceptionHandlerModel eh : method.exceptionHandlers()) {
                leaders.add(eh.handlerPc());
                // 将try起始边界标记为leader——确保try范围前的指令(如lock.lock())
                // 处于独立的基本块中,与try体分离
                if (eh.startPc() > 0) {
                    leaders.add(eh.startPc());
                }
                // 将try结束边界标记为leader——对try-finally至关重要:
                // 正常退出路径(位于endPc处)必须与try体处于不同的基本块.
                // 否则正常退出指令会被包含在try体块中,导致反编译时出现在try {}内部
                if (eh.endPc() > 0) {
                    leaders.add(eh.endPc());
                }
            }
        }

        // 第二步:按偏移量排序,构建基本块
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        Collections.sort(sortedLeaders);

        Map<Integer, Instruction> offsetToInsn = new LinkedHashMap<>();
        for (Instruction insn : instructions) {
            offsetToInsn.put(insn.offset(), insn);
        }

        List<BasicBlock> blocks = new ArrayList<>();
        int blockId = 0;
        int lastOffset = instructions.get(instructions.size() - 1).offset() + 1; // 最后一个指令偏移之后

        for (int i = 0; i < sortedLeaders.size(); i++) {
            int start = sortedLeaders.get(i);
            int end = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) : lastOffset;

            List<Instruction> blockInsns = new ArrayList<>();
            for (Instruction insn : instructions) {
                if (insn.offset() >= start && insn.offset() < end) {
                    blockInsns.add(insn);
                }
            }
            if (!blockInsns.isEmpty()) {
                blocks.add(new BasicBlock(blockId++, blockInsns));
            }
        }

        // 第三步:创建入口和出口基本块
        BasicBlock entry = new BasicBlock(blockId++, List.of());
        BasicBlock exit = new BasicBlock(blockId++, List.of());

        // 第四步:构建控制流边
        List<ControlFlowEdge> edges = new ArrayList<>();
        Map<Integer, BasicBlock> offsetToBlock = new HashMap<>();
        for (BasicBlock b : blocks) {
            offsetToBlock.put(b.startOffset(), b);
        }

        // 入口边:从入口块指向第一个实际基本块
        edges.add(new ControlFlowEdge(entry, blocks.get(0), EdgeKind.ENTRY, -1, null));

        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            Instruction last = block.lastInstruction();
            if (last == null) {
                continue;
            }

            if (last.isTerminal()) {
                // 先检查是否为switch指令(switch也是终结指令)
                if ("tableswitch".equals(last.mnemonic()) || "lookupswitch".equals(last.mnemonic())) {
                    boolean isTable = "tableswitch".equals(last.mnemonic());
                    int[] targets = last.jumpTargets();
                    java.util.List<Integer> operands = last.rawOperands();
                    // switch跳转目标数组的第一个元素是默认分支
                    if (targets.length > 0) {
                        BasicBlock defaultBlock = offsetToBlock.get(targets[0]);
                        if (defaultBlock != null) {
                            edges.add(ControlFlowEdge.switchDefault(block, defaultBlock));
                        }
                    }
                    // 其余目标为各个case分支
                    for (int t = 1; t < targets.length; t++) {
                        BasicBlock caseBlock = offsetToBlock.get(targets[t]);
                        if (caseBlock != null) {
                            int caseValue;
                            if (isTable) {
                                // TABLESWITCH操作数格式:[default偏移, low, high]
                                // case值 = low + (第几个case - 1)
                                int low = operands.size() > 1 ? operands.get(1) : 0;
                                caseValue = low + (t - 1);
                            } else {
                                // LOOKUPSWITCH rawOperands 格式:
                                // [default偏移, 匹配对数, match0, match1, ...]
                                // 跳转偏移量在 jumpTargets 中,不在 rawOperands 里.
                                int matchIdx = 2 + (t - 1);
                                caseValue = operands.size() > matchIdx ? operands.get(matchIdx) : t - 1;
                            }
                            edges.add(new ControlFlowEdge(block, caseBlock,
                                    EdgeKind.SWITCH_CASE, caseValue, null));
                        }
                    }
                } else {
                    // return/athrow等非switch终结指令 → 创建返回边指向出口块
                    edges.add(ControlFlowEdge.returnEdge(block, exit));
                }
            } else if ("goto".equals(last.mnemonic())) {
                // 无条件跳转
                BasicBlock target = offsetToBlock.get(last.jumpTargets()[0]);
                if (target != null) {
                    edges.add(ControlFlowEdge.gotoEdge(block, target));
                }
            } else if (last.mnemonic().startsWith("if")) {
                // 条件分支:创建真分支和假分支边.
                // 真分支 → 跳转目标:当比较结果为 true 时执行跳转
                // 假分支 → 直落(下一条指令):当比较结果为 false 时继续
                BasicBlock trueTarget = offsetToBlock.get(last.jumpTargets()[0]);
                BasicBlock falseTarget = (i + 1 < blocks.size()) ? blocks.get(i + 1) : exit;
                if (trueTarget != null) {
                    edges.add(ControlFlowEdge.trueBranch(block, trueTarget));
                    edges.add(ControlFlowEdge.falseBranch(block, falseTarget));
                }
            } else {
                // 普通指令:自然落入下一个基本块,若无后继则指向出口块
                if (i + 1 < blocks.size()) {
                    edges.add(ControlFlowEdge.fallThrough(block, blocks.get(i + 1)));
                } else {
                    edges.add(ControlFlowEdge.returnEdge(block, exit));
                }
            }
        }

        // 第五步:构建异常边
        List<ExceptionRange> exceptionRanges = new ArrayList<>();
        if (method.exceptionHandlers() != null) {
            int lastBytecodeOffset = offsetToInsn.keySet().stream()
                    .mapToInt(Integer::intValue).max().orElse(0) + 1;
            for (ExceptionHandlerModel eh : method.exceptionHandlers()) {
                BasicBlock handlerBlock = offsetToBlock.get(eh.handlerPc());
                if (handlerBlock == null) {
                    continue;
                }
                for (BasicBlock b : blocks) {
                    int blockEnd = getBlockEndOffset(b, blocks, lastBytecodeOffset);
                    // 重叠检测:包含指令落在try范围内的所有基本块,
                    // 即使基本块起始偏移在try范围之前也包含
                    if (b.startOffset() < eh.endPc() && blockEnd > eh.startPc()) {
                        edges.add(ControlFlowEdge.exception(b, handlerBlock, eh.catchType()));
                    }
                }
                // 确定try块:找到第一个与try范围重叠的基本块
                BasicBlock tryBlock = blocks.stream()
                        .filter(b -> {
                            int blockEnd = getBlockEndOffset(b, blocks, lastBytecodeOffset);
                            return b.startOffset() < eh.endPc() && blockEnd > eh.startPc();
                        })
                        .findFirst().orElse(null);
                if (tryBlock != null) {
                    exceptionRanges.add(new ExceptionRange(tryBlock, handlerBlock,
                            eh.catchType(), eh.startPc(), eh.endPc()));
                }
            }
        }

        // 组装所有基本块(入口 + 实际块 + 出口)
        List<BasicBlock> allBlocks = new ArrayList<>();
        allBlocks.add(entry);
        allBlocks.addAll(blocks);
        allBlocks.add(exit);

        return new ControlFlowGraph(method, entry, exit, allBlocks, edges, exceptionRanges);
    }
}
