package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 控制流结构化器——将包含 goto 的扁平 CFG 转化为结构化的 AST.
 *
 * <p>策略:不可变快照.每次折叠遍历返回一个新的 ControlFlowGraph.
 * 支配树/后支配树在每次成功折叠后重新计算.
 */
public class ControlFlowStructurer {

    /** 循环分析器 */
    private final LoopAnalyzer loopAnalyzer = new LoopAnalyzer();

    /** 分支分析器 */
    private final BranchAnalyzer branchAnalyzer = new BranchAnalyzer();

    /** switch 分析器 */
    private final SwitchAnalyzer switchAnalyzer = new SwitchAnalyzer();

    /** try-catch 分析器 */
    private final TryCatchAnalyzer tryCatchAnalyzer = new TryCatchAnalyzer();

    /** 不可归约图处理器(回退方案) */
    private final IrreducibleHandler irreducibleHandler = new IrreducibleHandler();

    /** finally 块识别与合并器 */
    private final FinallyRecognizer finallyRecognizer = new FinallyRecognizer();

    /** 块归约器(在结构化完成后将 CFG 转为 AST 语句) */
    private BlockReducer blockReducer;

    /**
     * 对方法的线性 IR 进行控制流结构化,生成 AST 方法体.
     *
     * @param ir  线性 IR
     * @param ctx 反编译上下文
     * @return 结构化方法对象,包含归约后的 AST 方法体
     */
    public StructuredMethod structure(LinearIr ir, DecompileContext ctx) {
        ControlFlowGraph graph = ir.controlFlowGraph();

        // 1. 计算初始支配树与后支配树
        DominatorTree dom = DominatorTree.compute(graph);
        PostDominatorTree postDom = PostDominatorTree.compute(graph);

        // 2. 分析 switch 和 try-catch(仅检测,暂不折叠)
        List<SwitchInfo> switchInfos = switchAnalyzer.analyze(graph, dom);
        List<TryCatchInfo> tryCatchInfos = tryCatchAnalyzer.analyze(graph);
        List<IfInfo> allIfInfos = branchAnalyzer.analyze(graph, dom, postDom);
        List<LoopInfo> allLoopInfos = loopAnalyzer.analyze(graph, dom);

        // 3. 构建注解映射
        Map<BasicBlock, LoopInfo> loopAnns = new HashMap<>();
        Map<BasicBlock, IfInfo> ifAnns = new HashMap<>();
        Map<BasicBlock, SwitchInfo> switchAnns = new HashMap<>();
        Map<BasicBlock, TryCatchInfo> tryCatchAnns = new HashMap<>();

        // 记录 switch 头块
        for (SwitchInfo si : switchInfos) {
            switchAnns.put(si.header(), si);
        }
        // 记录 try-catch 入口(以第一个 try 块为键,而非处理器)
        for (TryCatchInfo tci : tryCatchInfos) {
            tci.tryBlocks().stream()
                    .min(Comparator.comparingInt(BasicBlock::startOffset))
                    .ifPresent(tryEntry -> tryCatchAnns.put(tryEntry, tci));
        }
        // 从预折叠分析中记录 if/else 和循环注解.
        // 这些注解由 BlockReducer 直接用于构建 IfStatement/LoopStatement.
        // 我们不在 CFG 中折叠 if/else 块——折叠会破坏结构.
        for (IfInfo ifInfo : allIfInfos) {
            ifAnns.put(ifInfo.header(), ifInfo);
        }
        for (LoopInfo loop : allLoopInfos) {
            loopAnns.put(loop.header(), loop);
        }

        // 4. 迭代折叠:先循环(简化 CFG),再序列折叠.
        // If/else 块不折叠——BlockReducer 从注解构建它们.
        boolean changed = true;
        int maxIterations = Math.max(graph.blockCount() * 2, 100);
        while (changed && maxIterations-- > 0) {
            changed = false;

            // 4a. 循环(最内层优先)——折叠以简化嵌套的 CFG
            List<LoopInfo> loops = loopAnalyzer.analyze(graph, dom);
            if (!loops.isEmpty()) {
                loops = LoopAnalyzer.sortInnermostFirst(loops);
                for (LoopInfo loop : loops) {
                    BasicBlock oldHeader = loop.header();
                    int oldBlockCount = graph.blockCount();
                    graph = foldLoop(graph, loop, postDom);
                    // 将循环注解迁移到替换后的虚拟块
                    BasicBlock replacement = findReplacementBlock(graph, oldBlockCount);
                    loopAnns.remove(oldHeader);
                    if (replacement != null) {
                        loopAnns.put(replacement, loop);
                    }
                    changed = true;
                }
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                continue;
            }

            // 4b. 序列——合 并相邻的 fallthrough 块
            ControlFlowGraph prevGraph = graph;
            graph = foldSequences(graph);
            if (graph != prevGraph) {
                // 序列合并后更新 if/else 注解:若头块被合并到序列中,则更新键
                Map<BasicBlock, IfInfo> updatedIfAnns = new HashMap<>();
                for (var entry : ifAnns.entrySet()) {
                    BasicBlock header = entry.getKey();
                    BasicBlock current = findBlockInGraph(graph, header);
                    updatedIfAnns.put(current != null ? current : header, entry.getValue());
                }
                ifAnns = updatedIfAnns;

                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                changed = true;
            }
        }

        // 5. 不可归约图回退处理
        if (graph.blockCount() > 3) {
            graph = irreducibleHandler.handle(graph);
        }

        // 5b. 在最终折叠图上重新分析 if/else 和循环模式.
        // 预折叠分析结果可能在 CFG 折叠修改图后持有过时的块引用,
        // 因此需要从最终状态刷新.
        DominatorTree finalDom = DominatorTree.compute(graph);
        PostDominatorTree finalPostDom = PostDominatorTree.compute(graph);
        List<IfInfo> finalIfs = branchAnalyzer.analyze(graph, finalDom, finalPostDom);
        Map<BasicBlock, IfInfo> finalIfAnns = new HashMap<>();
        for (IfInfo ifInfo : finalIfs) {
            finalIfAnns.put(ifInfo.header(), ifInfo);
        }

        // 5c. 在最终折叠图上重新分析 try-catch(折叠可能替换了块,
        // 导致预折叠的 tryCatchAnns 键失效).
        List<TryCatchInfo> finalTryCatch = tryCatchAnalyzer.analyze(graph);
        Map<BasicBlock, TryCatchInfo> finalTryCatchAnns = new HashMap<>();
        for (TryCatchInfo tci : finalTryCatch) {
            BasicBlock tryEntry = tci.tryBlocks().stream()
                    .min(Comparator.comparingInt(BasicBlock::startOffset))
                    .orElse(null);
            if (tryEntry != null) {
                finalTryCatchAnns.put(tryEntry, tci);
            }
        }

        // 6. 生成带有结构注解的 AST
        blockReducer = new BlockReducer(!ir.method().isStatic());
        BlockStatement body = blockReducer.reduce(graph, ir, loopAnns, finalIfAnns, switchAnns, finalTryCatchAnns);

        // 7. 后处理:合并共享同一处理器的相邻 try-finally 块
        body = finallyRecognizer.merge(body, finalTryCatchAnns);

        return new StructuredMethod(ir.method(), ir, body, loopAnns, ifAnns, switchAnns, finalTryCatchAnns);
    }

    // ── 折叠操作 ────────────────────────────────────────

    /** 将循环体折叠为单个虚拟基本块 */
    private ControlFlowGraph foldLoop(ControlFlowGraph graph, LoopInfo loop,
                                      PostDominatorTree postDom) {
        BasicBlock virtualBlock = new BasicBlock(graph.blockCount() + 1000,
                flattenInstructions(loop.body(), graph));
        return buildFoldedGraph(graph, loop.body(), virtualBlock, loop.header());
    }

    /** 将 if-else 的两个分支折叠为单个虚拟基本块 */
    private ControlFlowGraph foldIf(ControlFlowGraph graph, IfInfo info) {
        Set<BasicBlock> allFolded = new HashSet<>();
        allFolded.addAll(info.thenBlocks());
        allFolded.addAll(info.elseBlocks());
        BasicBlock virtualBlock = new BasicBlock(graph.blockCount() + 1000,
                flattenInstructions(allFolded, graph));
        return buildFoldedGraph(graph, allFolded, virtualBlock, info.header());
    }

    /**
     * 将相邻的 fallthrough 块合并为序列.
     *
     * <p>尊重异常范围边界:具有不同异常覆盖范围的块(一个有异常边而另一个没有)
     * 不会被合并,从而确保 try 前代码(如 lock.lock())与 try 体保持分离.
     */
    private ControlFlowGraph foldSequences(ControlFlowGraph graph) {
        List<BasicBlock> regularBlocks = new ArrayList<>();
        for (BasicBlock b : graph.blocks()) {
            if (b != graph.entryBlock() && b != graph.exitBlock() && !b.instructions().isEmpty()) {
                regularBlocks.add(b);
            }
        }
        for (int i = 0; i < regularBlocks.size() - 1; i++) {
            BasicBlock b1 = regularBlocks.get(i);
            BasicBlock b2 = regularBlocks.get(i + 1);
            List<BasicBlock> succs = graph.successorsOf(b1);
            if (succs.size() == 1 && succs.get(0) == b2) {
                boolean onlyFallthrough = graph.outgoingOf(b1).stream()
                        .allMatch(e -> e.kind() == EdgeKind.FALL_THROUGH);
                if (onlyFallthrough && graph.predecessorsOf(b2).size() == 1) {
                    // 不合并具有不同异常覆盖范围的块.
                    // 这确保 try 前代码(无异常边)与 try 体(有指向处理器的异常边)保持分离.
                    boolean b1hasException = graph.outgoingOf(b1).stream()
                            .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
                    boolean b2hasException = graph.outgoingOf(b2).stream()
                            .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
                    if (b1hasException != b2hasException) {
                        continue;
                    }
                    List<Instruction> merged = new ArrayList<>();
                    merged.addAll(b1.instructions());
                    merged.addAll(b2.instructions());
                    BasicBlock mergedBlock = new BasicBlock(b1.id(), merged);
                    return buildFoldedGraph(graph, Set.of(b1, b2), mergedBlock, b1);
                }
            }
        }
        return graph;
    }

    // ── 辅助方法 ────────────────────────────────────────────────────

    /** 在折叠后的新图中按 ID 或起始偏移量查找等效块 */
    private BasicBlock findBlockInGraph(ControlFlowGraph graph, BasicBlock old) {
        for (BasicBlock b : graph.blocks()) {
            if (b.id() == old.id()) {
                return b;
            }
        }
        // 如果块已被合并,尝试按起始偏移量查找
        for (BasicBlock b : graph.blocks()) {
            if (b.startOffset() == old.startOffset()) {
                return b;
            }
        }
        return null;
    }

    /** 查找折叠操作创建的替换虚拟块.
     *  虚拟块的 id = 之前的块数量 + 1000. */
    private BasicBlock findReplacementBlock(ControlFlowGraph graph, int oldBlockCount) {
        int targetId = oldBlockCount + 1000;
        for (BasicBlock b : graph.blocks()) {
            if (b.id() == targetId) {
                return b;
            }
        }
        // 回退:查找任意非 entry/exit 的,id >= 1000 且有指令的块
        for (BasicBlock b : graph.blocks()) {
            if (b != graph.entryBlock() && b != graph.exitBlock()
                    && b.id() >= 1000 && !b.instructions().isEmpty()) {
                return b;
            }
        }
        return null;
    }

    /** 将多个基本块的指令展平为单一指令列表 */
    private List<Instruction> flattenInstructions(Set<BasicBlock> blocks, ControlFlowGraph graph) {
        List<Instruction> result = new ArrayList<>();
        for (BasicBlock b : graph.blocks()) {
            if (blocks.contains(b)) {
                result.addAll(b.instructions());
            }
        }
        return result;
    }

    /**
     * 构建折叠后的新控制流图.
     *
     * <p>将指定的一组块替换为单个替换块,并重新连接所有边.
     *
     * @param old         原始控制流图
     * @param folded      被折叠的块集合
     * @param replacement 替换后的虚拟块
     * @param anchor      锚点块(替换块插入到该块之后)
     * @return 折叠后的新控制流图
     */
    private ControlFlowGraph buildFoldedGraph(ControlFlowGraph old,
                                              Set<BasicBlock> folded,
                                              BasicBlock replacement,
                                              BasicBlock anchor) {
        List<BasicBlock> newBlocks = new ArrayList<>();
        for (BasicBlock b : old.blocks()) {
            if (!folded.contains(b)) {
                newBlocks.add(b);
            }
        }
        int anchorIdx = -1;
        for (int i = 0; i < newBlocks.size(); i++) {
            if (newBlocks.get(i).equals(anchor)) {
                anchorIdx = i;
                break;
            }
        }
        if (anchorIdx >= 0) {
            newBlocks.add(anchorIdx + 1, replacement);
        } else {
            newBlocks.add(replacement);
        }

        List<ControlFlowEdge> newEdges = new ArrayList<>();
        Set<BasicBlock> externalBlocks = new HashSet<>(newBlocks);
        externalBlocks.remove(replacement);

        // 将折叠块的前驱边重定向到替换块
        for (BasicBlock foldedBlock : folded) {
            for (ControlFlowEdge e : old.incomingOf(foldedBlock)) {
                if (!folded.contains(e.source()) && externalBlocks.contains(e.source())) {
                    newEdges.add(new ControlFlowEdge(e.source(), replacement,
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
        }
        // 将折叠块的后继边从替换块引出
        for (BasicBlock foldedBlock : folded) {
            for (ControlFlowEdge e : old.outgoingOf(foldedBlock)) {
                if (!folded.contains(e.target()) && externalBlocks.contains(e.target())) {
                    newEdges.add(new ControlFlowEdge(replacement, e.target(),
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
        }
        // 保留外部块之间的边
        for (BasicBlock b : externalBlocks) {
            for (ControlFlowEdge e : old.outgoingOf(b)) {
                if (!folded.contains(e.target()) && externalBlocks.contains(e.target())) {
                    if (!newEdges.contains(e)) {
                        newEdges.add(e);
                    }
                }
            }
        }
        return new ControlFlowGraph(old.method(), old.entryBlock(), old.exitBlock(),
                newBlocks, newEdges, old.exceptionRanges());
    }
}
