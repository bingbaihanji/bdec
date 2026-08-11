package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 源偏移量到IR指令列表的映射(用于合并/虚拟块的查找).
     *  每个字节码偏移量可能对应多条IR指令,因此存储为列表. */
    private Map<Integer, List<IrInstruction>> offsetToInstructions;

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
     * <p>同时使用两种查找策略并合并结果(按指令ID去重):
     * <ol>
     *   <li>blockId查找——对原始CFG块最准确,包含合成指令(sourceOffset=-1)</li>
     *   <li>偏移量查找——处理CFG折叠合并的块,其ID可能与原始IR blockId不匹配</li>
     * </ol>
     * </p>
     *
     * @param block 基本块
     * @return 该基本块内的指令列表
     */
    public List<IrInstruction> instructionsOf(BasicBlock block) {
        Set<Integer> seen = new HashSet<>();
        List<IrInstruction> result = new ArrayList<>();

        // 策略1:blockId查找——对原始CFG块最准确
        List<IrInstruction> byBlockId = blockInstructions.get(block.id());
        if (byBlockId != null) {
            for (IrInstruction insn : byBlockId) {
                if (seen.add(insn.id())) {
                    result.add(insn);
                }
            }
        }

        // 策略2:偏移量查找——捕获foldSequences合并块中
        // 来自被吸收子块(b2)的额外指令
        if (!block.instructions().isEmpty()) {
            buildOffsetMap();
            for (var instr : block.instructions()) {
                int offset = instr.offset();
                List<IrInstruction> irInsns = offsetToInstructions.get(offset);
                if (irInsns != null) {
                    for (IrInstruction insn : irInsns) {
                        if (seen.add(insn.id())) {
                            result.add(insn);
                        }
                    }
                }
            }
        }

        return result;
    }

    /** 惰性构建源偏移量到IR指令列表的映射 */
    private void buildOffsetMap() {
        if (offsetToInstructions == null) {
            offsetToInstructions = new HashMap<>();
            for (IrInstruction insn : instructions) {
                int offset = insn.sourceOffset();
                offsetToInstructions.computeIfAbsent(offset,
                        k -> new ArrayList<>()).add(insn);
            }
        }
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
        // 使偏移量缓存失效(指令已变更)
        offsetToInstructions = null;
    }
}
