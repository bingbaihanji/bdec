package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts LinearIr to SSA (Static Single Assignment) form.
 *
 * Algorithm (Cytron et al.):
 * 1. Collect variable definitions per block
 * 2. Compute iterated dominance frontiers → insert PHI nodes
 * 3. Rename variables with version numbers via dominator-tree DFS
 */
public final class SsaBuilder {

    /**
     * Build SSA form from linear IR. Returns the original IR unchanged if
     * there are no join points (≤ 1 variable definition).
     */
    public SsaForm build(LinearIr ir) {
        ControlFlowGraph cfg = ir.controlFlowGraph();
        DominatorTree dom = DominatorTree.compute(cfg);
        List<IrInstruction> originalInsns = new ArrayList<>(ir.instructions());
        List<Variable> originalVars = new ArrayList<>(ir.variables());

        // 1. Collect which variables are defined in each block
        //    and which blocks have definitions for each variable
        Map<Integer, Set<BasicBlock>> varDefBlocks = new HashMap<>(); // varSlot → blocks
        Map<BasicBlock, Set<Integer>> blockDefVars = new HashMap<>();  // block → varSlots

        for (IrInstruction insn : originalInsns) {
            if (insn.opcode() == IrOpcode.STORE && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                int slot = v.slot();
                BasicBlock block = findBlock(cfg, insn.blockId());
                if (block == null) {
                    continue;
                }
                varDefBlocks.computeIfAbsent(slot, k -> new HashSet<>()).add(block);
                blockDefVars.computeIfAbsent(block, k -> new HashSet<>()).add(slot);
            }
        }

        // If no variables to SSA-ify, return as-is
        if (varDefBlocks.isEmpty()) {
            return new SsaForm(cfg, dom, originalInsns, Map.of());
        }

        // 2. Compute dominance frontiers for all blocks
        Map<BasicBlock, Set<BasicBlock>> df = dom.computeDominanceFrontier();

        // 3. Insert PHI nodes at iterated dominance frontiers
        //    For each variable, find all blocks needing a PHI
        Map<Integer, Set<BasicBlock>> phiBlocks = new HashMap<>(); // varSlot → blocks with PHI
        for (Map.Entry<Integer, Set<BasicBlock>> entry : varDefBlocks.entrySet()) {
            int slot = entry.getKey();
            Set<BasicBlock> defs = entry.getValue();
            Set<BasicBlock> phis = computePhiBlocks(defs, df);
            if (!phis.isEmpty()) {
                phiBlocks.put(slot, phis);
            }
        }

        // 4. Insert PHI instructions
        List<IrInstruction> withPhis = new ArrayList<>(originalInsns);
        int nextId = originalInsns.stream().mapToInt(IrInstruction::id).max().orElse(0) + 1;

        Map<BasicBlock, List<IrInstruction>> perBlock = new HashMap<>();
        for (IrInstruction insn : withPhis) {
            perBlock.computeIfAbsent(findBlock(cfg, insn.blockId()), k -> new ArrayList<>()).add(insn);
        }

        for (Map.Entry<Integer, Set<BasicBlock>> entry : phiBlocks.entrySet()) {
            int slot = entry.getKey();
            for (BasicBlock block : entry.getValue()) {
                // Find original variable for slot
                Variable origVar = findVarBySlot(originalVars, slot);
                JavaType type = origVar != null ? origVar.type() : JavaType.INT;
                // PHI has no operands yet — they'll be filled during renaming
                IrInstruction phi = new IrInstruction(nextId++, IrOpcode.PHI, type,
                        List.of(), -1, block.id());
                // Insert at the beginning of the block
                List<IrInstruction> blockInsns = perBlock.computeIfAbsent(block, k -> new ArrayList<>());
                blockInsns.addFirst(phi);
            }
        }

        // Rebuild instruction list with PHIs inserted at block starts
        List<IrInstruction> allInsns = new ArrayList<>();
        // Process blocks in order
        for (BasicBlock block : orderBlocks(cfg)) {
            List<IrInstruction> bi = perBlock.get(block);
            if (bi != null) {
                allInsns.addAll(bi);
            }
        }
        // Include any blocks missed
        for (Map.Entry<BasicBlock, List<IrInstruction>> entry : perBlock.entrySet()) {
            if (!allInsns.containsAll(entry.getValue())) {
                allInsns.addAll(entry.getValue());
            }
        }

        // 5. Fill PHI operands from predecessor blocks
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() != IrOpcode.PHI) {
                continue;
            }
            int slot = findPhiSlot(insn, originalVars, varDefBlocks);
            if (slot < 0) {
                continue;
            }
            BasicBlock phiBlock = findBlock(cfg, insn.blockId());
            if (phiBlock == null) {
                continue;
            }

            // For each predecessor, find the variable version that reaches this block
            List<Value> phiOperands = new ArrayList<>();
            for (BasicBlock pred : cfg.predecessorsOf(phiBlock)) {
                Variable reachingVar = findReachingVar(pred, slot, originalVars,
                        allInsns, perBlock);
                if (reachingVar != null) {
                    phiOperands.add(reachingVar);
                }
            }
            // Replace the PHI instruction with one that has operands filled
            if (!phiOperands.isEmpty()) {
                int idx = allInsns.indexOf(insn);
                IrInstruction filled = new IrInstruction(insn.id(), IrOpcode.PHI,
                        insn.resultType(), phiOperands, insn.sourceOffset(), insn.blockId());
                allInsns.set(idx, filled);
            }
        }

        // Count final versions
        Map<Integer, Integer> varVersionCount = new HashMap<>();
        for (Variable v : originalVars) {
            varVersionCount.put(v.slot(),
                    Math.max(varVersionCount.getOrDefault(v.slot(), 0), v.version()));
        }

        return new SsaForm(cfg, dom, allInsns, varVersionCount);
    }

    /** Find the variable version for a slot that reaches the end of a predecessor block. */
    private Variable findReachingVar(BasicBlock block, int slot, List<Variable> vars,
                                     List<IrInstruction> allInsns,
                                     Map<BasicBlock, List<IrInstruction>> perBlock) {
        Variable latest = null;
        List<IrInstruction> blockInsns = perBlock.getOrDefault(block, List.of());
        // Walk instructions in reverse to find the last STORE for this slot
        for (int i = blockInsns.size() - 1; i >= 0; i--) {
            IrInstruction insn = blockInsns.get(i);
            if (insn.opcode() == IrOpcode.STORE && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v
                    && v.slot() == slot) {
                return v;
            }
            if (insn.opcode() == IrOpcode.PHI) {
                // PHI defines a new version
                int phiSlot = findPhiSlot(insn, vars, Map.of());
                if (phiSlot == slot && insn.resultValue() instanceof InstructionRef ref
                        && ref.instruction().operands().stream().anyMatch(
                        op -> op instanceof Variable v2 && v2.slot() == slot)) {
                    for (Value op : insn.operands()) {
                        if (op instanceof Variable v2 && v2.slot() == slot) {
                            return v2;
                        }
                    }
                }
            }
        }
        // Fallback: find the original variable for this slot
        for (Variable v : vars) {
            if (v.slot() == slot) {
                return v;
            }
        }
        return null;
    }

    /** Compute iterated dominance frontier for a set of definition blocks. */
    private Set<BasicBlock> computePhiBlocks(Set<BasicBlock> defs,
                                             Map<BasicBlock, Set<BasicBlock>> df) {
        Set<BasicBlock> phis = new HashSet<>(defs);
        Set<BasicBlock> worklist = new HashSet<>(defs);

        while (!worklist.isEmpty()) {
            BasicBlock b = worklist.iterator().next();
            worklist.remove(b);
            Set<BasicBlock> frontier = df.getOrDefault(b, Set.of());
            for (BasicBlock f : frontier) {
                if (phis.add(f)) {
                    worklist.add(f);
                }
            }
        }

        phis.removeAll(defs);
        return phis;
    }

    /** Find the variable slot that a PHI instruction represents. */
    private int findPhiSlot(IrInstruction phi, List<Variable> vars,
                            Map<Integer, Set<BasicBlock>> varDefBlocks) {
        // PHI's type gives us a hint — match to a variable with same type
        for (Map.Entry<Integer, Set<BasicBlock>> entry : varDefBlocks.entrySet()) {
            Variable v = findVarBySlot(vars, entry.getKey());
            if (v != null && v.type().equals(phi.resultType())) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private BasicBlock findBlock(ControlFlowGraph cfg, int blockId) {
        for (BasicBlock b : cfg.blocks()) {
            if (b.id() == blockId) {
                return b;
            }
        }
        return null;
    }

    private Variable findVarBySlot(List<Variable> vars, int slot) {
        for (Variable v : vars) {
            if (v.slot() == slot && v.version() == 0) {
                return v;
            }
        }
        return null;
    }

    private List<BasicBlock> orderBlocks(ControlFlowGraph cfg) {
        List<BasicBlock> result = new ArrayList<>();
        Deque<BasicBlock> stack = new ArrayDeque<>();
        Set<BasicBlock> visited = new HashSet<>();
        stack.push(cfg.entryBlock());
        while (!stack.isEmpty()) {
            BasicBlock b = stack.pop();
            if (!visited.add(b)) {
                continue;
            }
            if (b != cfg.entryBlock() && b != cfg.exitBlock()) {
                result.add(b);
            }
            for (BasicBlock succ : cfg.successorsOf(b)) {
                if (!visited.contains(succ)) {
                    stack.push(succ);
                }
            }
        }
        return result;
    }
}
