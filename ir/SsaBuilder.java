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
 * SSA(静态单赋值)形式构造器.
 * <p>
 * 将线性IR转换为SSA形式.采用Cytron等人的经典算法:
 * </p>
 * <ol>
 *   <li>收集每个基本块中的变量定义</li>
 *   <li>计算迭代支配边界,在适当位置插入phi函数节点</li>
 *   <li>通过支配树的DFS遍历重命名变量并赋版本号</li>
 * </ol>
 */
public final class SsaBuilder {

    /**
     * 从线性IR构建SSA形式.
     * 如果没有汇合点(变量定义数不超过1),则返回原始IR不变.
     *
     * @param ir 线性IR
     * @return 转换后的SSA形式
     */
    public SsaForm build(LinearIr ir) {
        ControlFlowGraph cfg = ir.controlFlowGraph();
        DominatorTree dom = DominatorTree.compute(cfg);
        List<IrInstruction> originalInsns = new ArrayList<>(ir.instructions());
        List<Variable> originalVars = new ArrayList<>(ir.variables());

        // 第一步:收集每个基本块中定义的变量
        // varDefBlocks: 变量槽位 → 定义该变量的基本块集合
        // blockDefVars:  基本块 → 该块中定义的变量槽位集合
        Map<Integer, Set<BasicBlock>> varDefBlocks = new HashMap<>();
        Map<BasicBlock, Set<Integer>> blockDefVars = new HashMap<>();

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

        // 没有需要SSA化的变量则直接返回
        if (varDefBlocks.isEmpty()) {
            return new SsaForm(cfg, dom, originalInsns, Map.of());
        }

        // 第二步:计算所有基本块的支配边界
        Map<BasicBlock, Set<BasicBlock>> df = dom.computeDominanceFrontier();

        // 第三步:在迭代支配边界处插入phi函数节点
        // 对每个变量,找出所有需要phi的基本块
        Map<Integer, Set<BasicBlock>> phiBlocks = new HashMap<>();
        for (Map.Entry<Integer, Set<BasicBlock>> entry : varDefBlocks.entrySet()) {
            int slot = entry.getKey();
            Set<BasicBlock> defs = entry.getValue();
            Set<BasicBlock> phis = computePhiBlocks(defs, df);
            if (!phis.isEmpty()) {
                phiBlocks.put(slot, phis);
            }
        }

        // 第四步:在指令列表中插入phi指令
        List<IrInstruction> withPhis = new ArrayList<>(originalInsns);
        int nextId = originalInsns.stream().mapToInt(IrInstruction::id).max().orElse(0) + 1;

        Map<BasicBlock, List<IrInstruction>> perBlock = new HashMap<>();
        for (IrInstruction insn : withPhis) {
            perBlock.computeIfAbsent(findBlock(cfg, insn.blockId()), k -> new ArrayList<>()).add(insn);
        }

        for (Map.Entry<Integer, Set<BasicBlock>> entry : phiBlocks.entrySet()) {
            int slot = entry.getKey();
            for (BasicBlock block : entry.getValue()) {
                // 找到对应槽位的原始变量
                Variable origVar = findVarBySlot(originalVars, slot);
                JavaType type = origVar != null ? origVar.type() : JavaType.INT;
                // phi的操作数暂时为空——将在重命名阶段填充
                IrInstruction phi = new IrInstruction(nextId++, IrOpcode.PHI, type,
                        List.of(), -1, block.id());
                // 插入到基本块的开头
                List<IrInstruction> blockInsns = perBlock.computeIfAbsent(block, k -> new ArrayList<>());
                blockInsns.addFirst(phi);
            }
        }

        // 按基本块顺序重建指令列表,phi指令已插入到各块开头
        List<IrInstruction> allInsns = new ArrayList<>();
        for (BasicBlock block : orderBlocks(cfg)) {
            List<IrInstruction> bi = perBlock.get(block);
            if (bi != null) {
                allInsns.addAll(bi);
            }
        }
        // 包含可能遗漏的基本块
        for (Map.Entry<BasicBlock, List<IrInstruction>> entry : perBlock.entrySet()) {
            if (!allInsns.containsAll(entry.getValue())) {
                allInsns.addAll(entry.getValue());
            }
        }

        // 第五步:从前驱基本块填充phi操作数
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

            // 对每个前驱找出到达该块的变量版本
            List<Value> phiOperands = new ArrayList<>();
            for (BasicBlock pred : cfg.predecessorsOf(phiBlock)) {
                Variable reachingVar = findReachingVar(pred, slot, originalVars,
                        allInsns, perBlock);
                if (reachingVar != null) {
                    phiOperands.add(reachingVar);
                }
            }
            // 用填充了操作数的phi指令替换原phi
            if (!phiOperands.isEmpty()) {
                int idx = allInsns.indexOf(insn);
                IrInstruction filled = new IrInstruction(insn.id(), IrOpcode.PHI,
                        insn.resultType(), phiOperands, insn.sourceOffset(), insn.blockId());
                allInsns.set(idx, filled);
            }
        }

        // 统计最终版本数
        Map<Integer, Integer> varVersionCount = new HashMap<>();
        for (Variable v : originalVars) {
            varVersionCount.put(v.slot(),
                    Math.max(varVersionCount.getOrDefault(v.slot(), 0), v.version()));
        }

        return new SsaForm(cfg, dom, allInsns, varVersionCount);
    }

    /**
     * 查找在指定前驱基本块末尾处到达的给定槽位的变量版本.
     * 从基本块末尾反向扫描,找到该槽位的最后一次STORE操作.
     */
    private Variable findReachingVar(BasicBlock block, int slot, List<Variable> vars,
                                     List<IrInstruction> allInsns,
                                     Map<BasicBlock, List<IrInstruction>> perBlock) {
        Variable latest = null;
        List<IrInstruction> blockInsns = perBlock.getOrDefault(block, List.of());
        // 反向遍历指令,找到该槽位的最后一次STORE
        for (int i = blockInsns.size() - 1; i >= 0; i--) {
            IrInstruction insn = blockInsns.get(i);
            if (insn.opcode() == IrOpcode.STORE && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v
                    && v.slot() == slot) {
                return v;
            }
            if (insn.opcode() == IrOpcode.PHI) {
                // phi定义了一个新版本,检查是否与目标槽位匹配
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
        // 回退:找到该槽位的原始变量
        for (Variable v : vars) {
            if (v.slot() == slot) {
                return v;
            }
        }
        return null;
    }

    /**
     * 计算一组定义基本块的迭代支配边界.
     * 从定义块集合出发,反复取支配边界直到不动点,
     * 最后移除定义块自身,得到需要插入phi的块集合.
     */
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

        phis.removeAll(defs); // phi插入位置不应在定义块本身
        return phis;
    }

    /**
     * 查找phi指令所代表的变量槽位.
     * 通过phi的结果类型匹配原始变量来确定槽位.
     */
    private int findPhiSlot(IrInstruction phi, List<Variable> vars,
                            Map<Integer, Set<BasicBlock>> varDefBlocks) {
        for (Map.Entry<Integer, Set<BasicBlock>> entry : varDefBlocks.entrySet()) {
            Variable v = findVarBySlot(vars, entry.getKey());
            if (v != null && v.type().equals(phi.resultType())) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * 根据基本块ID在控制流图中查找对应的基本块.
     */
    private BasicBlock findBlock(ControlFlowGraph cfg, int blockId) {
        for (BasicBlock b : cfg.blocks()) {
            if (b.id() == blockId) {
                return b;
            }
        }
        return null;
    }

    /**
     * 在变量列表中按槽位查找版本号为0的原始变量.
     */
    private Variable findVarBySlot(List<Variable> vars, int slot) {
        for (Variable v : vars) {
            if (v.slot() == slot && v.version() == 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 使用DFS对控制流图的基本块进行拓扑排序.
     */
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
