package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LinearIr {

    private final MethodModel method;

    private final ControlFlowGraph cfg;

    private final List<IrInstruction> instructions;

    private final Map<Integer, List<IrInstruction>> blockInstructions;

    private final List<Variable> variables;

    private boolean ssaOptimized;

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

    public MethodModel method() {return method;}

    public ControlFlowGraph controlFlowGraph() {return cfg;}

    public List<IrInstruction> instructions() {return instructions;}

    public List<Variable> variables() {return Collections.unmodifiableList(variables);}

    public List<IrInstruction> instructionsOf(BasicBlock block) {
        return blockInstructions.getOrDefault(block.id(), List.of());
    }

    public boolean ssaOptimized() {return ssaOptimized;}

    public void setSsaOptimized(boolean v) {this.ssaOptimized = v;}

    public void addVariable(Variable v) {variables.add(v);}
}
