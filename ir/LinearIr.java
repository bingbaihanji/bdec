package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 线性IR.
 * <p>
 * 表示一个方法在转换为寄存器式IR后的完整中间表示.
 * 包含指令列表,变量列表,基本块到指令的映射,以及与原始方法和控制流图的关联.
 * 支持指令替换和SSA优化标记.
 * </p>
 */
public class LinearIr {

    /** 关联的方法模型 */
    private final MethodModel method;

    /** 关联的控制流图 */
    private final ControlFlowGraph cfg;

    /** IR指令列表(不可变) */
    private final List<IrInstruction> instructions;

    /** 基本块ID到指令列表的映射 */
    private final Map<Integer, List<IrInstruction>> blockInstructions;

    /** 变量列表 */
    private final List<Variable> variables;

    /** 是否已进行SSA优化 */
    private boolean ssaOptimized;

    /**
     * 构造线性IR.
     *
     * @param method       方法模型
     * @param cfg          控制流图
     * @param instructions IR指令列表
     * @param variables    变量列表
     */
    public LinearIr(MethodModel method, ControlFlowGraph cfg,
                    List<IrInstruction> instructions, List<Variable> variables) {
        this.method = method;
        this.cfg = cfg;
        this.instructions = List.copyOf(instructions);
        this.variables = new ArrayList<>(variables);
        this.blockInstructions = new HashMap<>();
        for (IrInstruction insn : instructions) {
            blockInstructions.computeIfAbsent(insn.blockId(), k -> new ArrayList<>()).add(insn);
        }
    }

    /** @return 方法模型 */
    public MethodModel method() {return method;}

    /** @return 控制流图 */
    public ControlFlowGraph controlFlowGraph() {return cfg;}

    /** @return IR指令列表 */
    public List<IrInstruction> instructions() {return instructions;}

    /** @return 变量的不可变列表 */
    public List<Variable> variables() {return Collections.unmodifiableList(variables);}

    /**
     * 获取指定基本块内的所有指令.
     *
     * @param block 基本块
     * @return 该基本块内的指令列表
     */
    public List<IrInstruction> instructionsOf(BasicBlock block) {
        return blockInstructions.getOrDefault(block.id(), List.of());
    }

    /** @return 是否已进行SSA优化 */
    public boolean ssaOptimized() {return ssaOptimized;}

    /** 设置SSA优化标记 */
    public void setSsaOptimized(boolean v) {this.ssaOptimized = v;}

    /** 添加一个变量 */
    public void addVariable(Variable v) {variables.add(v);}

    /**
     * 替换整个指令列表(由语义分析pass调用,用于删除或重写指令).
     * 同时重建基本块到指令的映射.
     *
     * @param newInstructions 新的指令列表
     */
    public void replaceInstructions(List<IrInstruction> newInstructions) {
        java.lang.reflect.Field insnsField;
        try {
            insnsField = LinearIr.class.getDeclaredField("instructions");
            insnsField.setAccessible(true);
            insnsField.set(this, List.copyOf(newInstructions));
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace instructions", e);
        }
        // 重建基本块到指令的映射
        blockInstructions.clear();
        for (IrInstruction insn : newInstructions) {
            blockInstructions.computeIfAbsent(insn.blockId(),
                    k -> new ArrayList<>()).add(insn);
        }
    }
}
