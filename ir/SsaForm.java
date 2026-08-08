package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSA (Static Single Assignment) form of a method's IR.
 * Each variable is defined exactly once; values flow through PHI nodes at join points.
 */
public class SsaForm {

    private final ControlFlowGraph cfg;

    private final DominatorTree dominatorTree;

    private final List<IrInstruction> instructions;

    private final Map<Integer, List<IrInstruction>> blockInstructions; // blockId → instructions

    private final Map<Integer, Integer> varVersionCount; // originalVarId → version count

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

    public ControlFlowGraph cfg() {return cfg;}

    public DominatorTree dominatorTree() {return dominatorTree;}

    public List<IrInstruction> instructions() {return instructions;}

    public Map<Integer, Integer> varVersionCount() {return varVersionCount;}

    public List<IrInstruction> instructionsOf(BasicBlock block) {
        return blockInstructions.getOrDefault(block.id(), List.of());
    }

    /** Get the maximum version number for a given original variable slot. */
    public int maxVersion(int originalSlot) {
        return varVersionCount.getOrDefault(originalSlot, 0);
    }
}
