package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSA(静态单赋值)形式.
 * <p>
 * 表示一个方法IR的SSA形式.在SSA形式中,每个变量仅被定义一次,
 * 不同控制流路径上的值通过phi函数节点在汇合点合并.
 * 提供按基本块查询指令,版本号管理等功能.
 * </p>
 */
public class SsaForm {

    /** 关联的控制流图 */
    private final ControlFlowGraph cfg;

    /** 支配树 */
    private final DominatorTree dominatorTree;

    /** SSA指令列表(不可变) */
    private final List<IrInstruction> instructions;

    /** 基本块ID到指令列表的映射 */
    private final Map<Integer, List<IrInstruction>> blockInstructions;

    /** 原始变量ID到SSA版本数的映射 */
    private final Map<Integer, Integer> varVersionCount;

    /**
     * 构造SSA形式.
     *
     * @param cfg             控制流图
     * @param dominatorTree   支配树
     * @param instructions    SSA指令列表
     * @param varVersionCount 变量版本数映射
     */
    public SsaForm(ControlFlowGraph cfg, DominatorTree dominatorTree,
                   List<IrInstruction> instructions,
                   Map<Integer, Integer> varVersionCount) {
        this.cfg = cfg;
        this.dominatorTree = dominatorTree;
        this.instructions = List.copyOf(instructions);
        this.varVersionCount = Map.copyOf(varVersionCount);

        this.blockInstructions = new HashMap<>();
        for (IrInstruction insn : instructions) {
            blockInstructions.computeIfAbsent(insn.blockId(), k -> new ArrayList<>()).add(insn);
        }
    }

    /** @return 控制流图 */
    public ControlFlowGraph cfg() {return cfg;}

    /** @return 支配树 */
    public DominatorTree dominatorTree() {return dominatorTree;}

    /** @return SSA指令列表 */
    public List<IrInstruction> instructions() {return instructions;}

    /** @return 变量版本数映射 */
    public Map<Integer, Integer> varVersionCount() {return varVersionCount;}

    /**
     * 获取指定基本块内的所有SSA指令.
     *
     * @param block 基本块
     * @return 该块内的指令列表
     */
    public List<IrInstruction> instructionsOf(BasicBlock block) {
        return blockInstructions.getOrDefault(block.id(), List.of());
    }

    /**
     * 获取指定原始变量槽位的最大版本号.
     *
     * @param originalSlot 原始变量槽位
     * @return 最大版本号
     */
    public int maxVersion(int originalSlot) {
        return varVersionCount.getOrDefault(originalSlot, 0);
    }
}
