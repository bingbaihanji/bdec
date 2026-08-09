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

        // 1. Find leaders — jump targets AND fall-through boundaries
        Set<Integer> leaders = new LinkedHashSet<>();
        leaders.add(instructions.get(0).offset());

        for (int i = 0; i < instructions.size(); i++) {
            Instruction insn = instructions.get(i);
            for (int target : insn.jumpTargets()) {
                leaders.add(target);
            }
            // For instructions that don't fall through (goto, if*, tableswitch,
            // lookupswitch, athrow), the next instruction is only reachable via
            // an explicit branch from elsewhere → must be a leader.
            if (!insn.canFallThrough() && i + 1 < instructions.size()) {
                leaders.add(instructions.get(i + 1).offset());
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
                    boolean isTable = last.mnemonic().equals("tableswitch");
                    int[] targets = last.jumpTargets();
                    java.util.List<Integer> operands = last.rawOperands();
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
                            int caseValue;
                            if (isTable) {
                                // TABLESWITCH: operands=[defaultOffset, low, high]
                                int low = operands.size() > 1 ? operands.get(1) : 0;
                                caseValue = low + (t - 1);
                            } else {
                                // LOOKUPSWITCH: operands=[defaultOffset, npairs, match0, off0, ...]
                                int matchIdx = 2 + (t - 1) * 2;
                                caseValue = operands.size() > matchIdx ? operands.get(matchIdx) : t - 1;
                            }
                            edges.add(new ControlFlowEdge(block, caseBlock,
                                    EdgeKind.SWITCH_CASE, caseValue, null));
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
            int lastBytecodeOffset = offsetToInsn.keySet().stream()
                    .mapToInt(Integer::intValue).max().orElse(0) + 1;
            for (ExceptionHandlerModel eh : method.exceptionHandlers()) {
                BasicBlock handlerBlock = offsetToBlock.get(eh.handlerPc());
                if (handlerBlock == null) {
                    continue;
                }
                for (BasicBlock b : blocks) {
                    int blockEnd = getBlockEndOffset(b, blocks, lastBytecodeOffset);
                    // Overlap check: include blocks whose instructions fall within
                    // the try range, even if the block starts before the range.
                    if (b.startOffset() < eh.endPc() && blockEnd > eh.startPc()) {
                        edges.add(ControlFlowEdge.exception(b, handlerBlock, eh.catchType()));
                    }
                }
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

        List<BasicBlock> allBlocks = new ArrayList<>();
        allBlocks.add(entry);
        allBlocks.addAll(blocks);
        allBlocks.add(exit);

        return new ControlFlowGraph(method, entry, exit, allBlocks, edges, exceptionRanges);
    }

    /** Compute the end offset of a block (start offset of next block, or past-end). */
    private static int getBlockEndOffset(BasicBlock b, List<BasicBlock> blocks, int lastOffset) {
        for (BasicBlock next : blocks) {
            if (next.startOffset() > b.startOffset()) {
                return next.startOffset();
            }
        }
        return lastOffset;
    }
}
