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

public final class CfgBuilder {

    public ControlFlowGraph build(MethodModel method) {
        List<Instruction> instructions = method.instructions();
        if (instructions == null || instructions.isEmpty()) {
            BasicBlock entry = new BasicBlock(0, List.of());
            BasicBlock exit = new BasicBlock(1, List.of());
            return new ControlFlowGraph(method, entry, exit,
                    List.of(entry, exit), List.of(), List.of());
        }

        // 1. Find leaders
        Set<Integer> leaders = new LinkedHashSet<>();
        leaders.add(instructions.get(0).offset());

        for (Instruction insn : instructions) {
            for (int target : insn.jumpTargets()) {
                leaders.add(target);
            }
            if (!insn.canFallThrough() && !insn.isTerminal()) {
                // Last instruction in the method
            }
        }

        // Exception handler entries are leaders
        if (method.exceptionHandlers() != null) {
            for (ExceptionHandlerModel eh : method.exceptionHandlers()) {
                leaders.add(eh.handlerPc());
            }
        }

        // 2. Build blocks from sorted leaders
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        Collections.sort(sortedLeaders);

        Map<Integer, Instruction> offsetToInsn = new LinkedHashMap<>();
        for (Instruction insn : instructions) {
            offsetToInsn.put(insn.offset(), insn);
        }

        List<BasicBlock> blocks = new ArrayList<>();
        int blockId = 0;
        int lastOffset = instructions.get(instructions.size() - 1).offset() + 1; // past end

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

        // 3. Create entry and exit blocks
        BasicBlock entry = new BasicBlock(blockId++, List.of());
        BasicBlock exit = new BasicBlock(blockId++, List.of());

        // 4. Build edges
        List<ControlFlowEdge> edges = new ArrayList<>();
        Map<Integer, BasicBlock> offsetToBlock = new HashMap<>();
        for (BasicBlock b : blocks) {
            offsetToBlock.put(b.startOffset(), b);
        }

        edges.add(new ControlFlowEdge(entry, blocks.get(0), EdgeKind.ENTRY, -1, null));

        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            Instruction last = block.lastInstruction();
            if (last == null) {
                continue;
            }

            if (last.isTerminal()) {
                // Check for switch before treating as return
                if (last.mnemonic().equals("tableswitch") || last.mnemonic().equals("lookupswitch")) {
                    int[] targets = last.jumpTargets();
                    // First target is default
                    if (targets.length > 0) {
                        BasicBlock defaultBlock = offsetToBlock.get(targets[0]);
                        if (defaultBlock != null) {
                            edges.add(ControlFlowEdge.switchDefault(block, defaultBlock));
                        }
                    }
                    // Remaining targets are cases
                    for (int t = 1; t < targets.length; t++) {
                        BasicBlock caseBlock = offsetToBlock.get(targets[t]);
                        if (caseBlock != null) {
                            edges.add(new ControlFlowEdge(block, caseBlock,
                                    EdgeKind.SWITCH_CASE, t - 1, null));
                        }
                    }
                } else {
                    edges.add(ControlFlowEdge.returnEdge(block, exit));
                }
            } else if (last.mnemonic().equals("goto")) {
                BasicBlock target = offsetToBlock.get(last.jumpTargets()[0]);
                if (target != null) {
                    edges.add(ControlFlowEdge.gotoEdge(block, target));
                }
            } else if (last.mnemonic().startsWith("if")) {
                BasicBlock trueTarget = offsetToBlock.get(last.jumpTargets()[0]);
                BasicBlock falseTarget = (i + 1 < blocks.size()) ? blocks.get(i + 1) : exit;
                if (trueTarget != null) {
                    edges.add(ControlFlowEdge.trueBranch(block, trueTarget));
                    edges.add(ControlFlowEdge.falseBranch(block, falseTarget));
                }
            } else {
                if (i + 1 < blocks.size()) {
                    edges.add(ControlFlowEdge.fallThrough(block, blocks.get(i + 1)));
                } else {
                    edges.add(ControlFlowEdge.returnEdge(block, exit));
                }
            }
        }

        // 5. Exception edges
        List<ExceptionRange> exceptionRanges = new ArrayList<>();
        if (method.exceptionHandlers() != null) {
            for (ExceptionHandlerModel eh : method.exceptionHandlers()) {
                BasicBlock handlerBlock = offsetToBlock.get(eh.handlerPc());
                if (handlerBlock == null) {
                    continue;
                }
                for (BasicBlock b : blocks) {
                    if (b.startOffset() >= eh.startPc() && b.startOffset() < eh.endPc()) {
                        edges.add(ControlFlowEdge.exception(b, handlerBlock, eh.catchType()));
                    }
                }
                // Find first block in try range as tryBlock
                BasicBlock tryBlock = blocks.stream()
                        .filter(b -> b.startOffset() >= eh.startPc() && b.startOffset() < eh.endPc())
                        .findFirst().orElse(null);
                if (tryBlock != null) {
                    exceptionRanges.add(new ExceptionRange(tryBlock, handlerBlock,
                            eh.catchType(), eh.startPc(), eh.endPc()));
                }
            }
        }

        List<BasicBlock> allBlocks = new ArrayList<>();
        allBlocks.add(entry);
        allBlocks.addAll(blocks);
        allBlocks.add(exit);

        return new ControlFlowGraph(method, entry, exit, allBlocks, edges, exceptionRanges);
    }
}
