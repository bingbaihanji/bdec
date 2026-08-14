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

    /** 折叠虚拟块的单调递增 ID 计数器.
     *  <p>BasicBlock 的 equals/hashCode 仅基于 id——若使用 blockCount+1000
     *  作为虚拟块 id,连续折叠时 blockCount 可能不变(折叠 N 块再新增 1 块),
     *  导致同一图中出现多个 id 相同的块,使 CFG 的 HashMap 和注解映射键碰撞,
     *  边丢失,结构破坏.单调计数器保证全局唯一.</p> */
    private int nextVirtualBlockId = 1_000_000;

    /** 最近一次折叠创建的虚拟块 ID(供 findReplacementBlock 查找) */
    private int lastCreatedVirtualId = -1;

    /**
     * 对方法的线性 IR 进行控制流结构化,生成 AST 方法体.
     *
     * @param ir  线性 IR
     * @param ctx 反编译上下文
     * @return 结构化方法对象,包含归约后的 AST 方法体
     */
    public StructuredMethod structure(LinearIr ir, DecompileContext ctx) {
        // 每个方法独立分配虚拟块 ID(从 1_000_000 开始,避免与原始块 0..N 冲突)
        nextVirtualBlockId = 1_000_000;
        lastCreatedVirtualId = -1;

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
        // try-catch 使用列表而非映射:同一处理器(如 finally)可能对应
        // 多个异常范围,映射键会互相覆盖导致 catch 子句丢失.
        List<TryCatchInfo> tryCatchAnns = new ArrayList<>(tryCatchInfos);

        // 记录 switch 头块
        for (SwitchInfo si : switchInfos) {
            switchAnns.put(si.header(), si);
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
                    // 跳过包含 switch 的循环(头或体):
                    // 模式匹配 switch(typeSwitch) 的 when 守卫会生成回边
                    //(切换到重启索引的重试循环).折叠此循环会破坏 switch 结构.
                    // 循环头可能只是 typeSwitch 调用的块(不含 switch 指令),
                    // 但循环体内包含 tableswitch 块,因此也需要检查.
                    if (isSwitchHeader(loop.header(), switchAnns)
                            || loopContainsSwitch(loop, switchAnns)) {
                        continue;
                    }
                    // 跳过体内含内部分支(continue/break 模式)的循环:
                    // 折叠会把体内条件分支扁平化丢失,
                    // 由 BlockReducer 直接结构化翻译循环体.
                    if (hasInternalBranches(loop, graph)) {
                        continue;
                    }
                    BasicBlock oldHeader = loop.header();
                    graph = foldLoop(graph, loop, postDom);
                    // 将循环注解迁移到替换后的虚拟块
                    BasicBlock replacement = findReplacementBlock(graph);
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

        // 5c. 在最终折叠图上重新分析 switch.
        // 循环折叠可能替换了 switch 头块,使预折叠的 switchAnns 键失效.
        // 模式匹配 switch(typeSwitch) 特别容易受此影响——其 when 守卫
        // 会生成回边,导致 switch 头被 LoopAnalyzer 折叠.
        List<SwitchInfo> finalSwitches = switchAnalyzer.analyze(graph, finalDom);
        Map<BasicBlock, SwitchInfo> finalSwitchAnns = new HashMap<>();
        for (SwitchInfo si : finalSwitches) {
            finalSwitchAnns.put(si.header(), si);
        }

        // 5d. 在最终折叠图上重新分析 try-catch(折叠可能替换了块,
        // 导致预折叠的 tryCatchAnns 引用失效).
        // 使用列表:同一 try 区域可有多个处理器(catch + finally),
        // 以任意块为键的映射都会互相覆盖丢失处理器.
        List<TryCatchInfo> finalTryCatch = tryCatchAnalyzer.analyze(graph);

        // 6. 生成带有结构注解的 AST
        if (ctx != null && ctx.config() != null && ctx.config().debugDumpCfg()) {
            dumpGraph("=== FINAL FOLDED CFG [" + ir.method().name() + "] ===", graph,
                    finalIfAnns, finalSwitchAnns, loopAnns);
        }
        blockReducer = new BlockReducer(!ir.method().isStatic());
        BlockStatement body = blockReducer.reduce(graph, ir, loopAnns, finalIfAnns, finalSwitchAnns, finalTryCatch);

        // 7. 后处理:合并共享同一处理器的相邻 try-finally 块
        body = finallyRecognizer.merge(body, finalTryCatch);

        return new StructuredMethod(ir.method(), ir, body, loopAnns, ifAnns, switchAnns, finalTryCatch);
    }

    // ── 折叠操作 ────────────────────────────────────────

    /** 调试:打印折叠后 CFG 的块/边/注解(由 debugDumpCfg 开关控制) */
    private void dumpGraph(String title, ControlFlowGraph graph,
                           Map<BasicBlock, IfInfo> ifAnns,
                           Map<BasicBlock, SwitchInfo> switchAnns,
                           Map<BasicBlock, LoopInfo> loopAnns) {
        System.err.println(title);
        for (BasicBlock b : graph.blocks()) {
            String tag = "";
            if (b == graph.entryBlock()) {
                tag += "[ENTRY] ";
            }
            if (b == graph.exitBlock()) {
                tag += "[EXIT] ";
            }
            if (loopAnns.containsKey(b)) {
                tag += "[LOOP] ";
            }
            if (switchAnns.containsKey(b)) {
                tag += "[SWITCH] ";
            }
            if (ifAnns.containsKey(b)) {
                tag += "[IF] ";
            }
            System.err.println("  B" + b.id() + " " + tag
                    + " off=" + b.startOffset() + " insns=" + b.instructions().size());
            for (var e : graph.outgoingOf(b)) {
                System.err.println("    -> B" + e.target().id() + " [" + e.kind()
                        + (e.switchKey() >= 0 ? " key=" + e.switchKey() : "")
                        + (e.catchType() != null ? " catch=" + e.catchType() : "") + "]");
            }
        }
    }

    /** 将循环体折叠为单个虚拟基本块 */
    private ControlFlowGraph foldLoop(ControlFlowGraph graph, LoopInfo loop,
                                      PostDominatorTree postDom) {
        lastCreatedVirtualId = nextVirtualBlockId++;
        BasicBlock virtualBlock = new BasicBlock(lastCreatedVirtualId,
                flattenInstructions(loop.body(), graph));
        return buildFoldedGraph(graph, loop.body(), virtualBlock, loop.header());
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
                    // 不合并包含 switch 指令的块.
                    // switch 块合并后 tableswitch 不再在末尾,导致 endsWithSwitch() 失效.
                    if (b1.containsSwitch() || b2.containsSwitch()) {
                        continue;
                    }
                    // switch 块的出边目标不能与任何块合并
                    if (isSwitchTarget(b1, graph) || isSwitchTarget(b2, graph)) {
                        continue;
                    }
                    // 检查 b1 是否有 SWITCH_CASE 出边(防止 switch 头与后续合并)
                    if (hasSwitchOutgoing(b1, graph) || hasSwitchOutgoing(b2, graph)) {
                        continue;
                    }
                    // 不合并处理器块(有 EXCEPTION 入边)与后续非处理器块.
                    // 否则 catch/finally 体泄露到后续代码中.
                    boolean b1isHandler = graph.incomingOf(b1).stream()
                            .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
                    boolean b2isHandler = graph.incomingOf(b2).stream()
                            .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
                    if (b1isHandler != b2isHandler) {
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

    /** 检查循环体内是否含内部分支(continue/break 模式).
     *  <p>非头块以条件跳转结尾(体内 if),或存在指向非头体内块的 GOTO
     *  (continue 桥接块)——折叠会扁平化这些分支,丢失控制流结构.
     *  while 风格的测试在头块,增量在 latch 的普通循环不受影响.</p> */
    private boolean hasInternalBranches(LoopInfo loop, ControlFlowGraph graph) {
        for (BasicBlock b : loop.body()) {
            if (b == loop.header()) {
                continue;
            }
            if (b.endsWithConditionalJump()) {
                return true;
            }
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() == EdgeKind.GOTO) {
                    BasicBlock t = e.target();
                    if (t != loop.header() && loop.body().contains(t)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 检查循环体中是否包含 switch 块 */
    private boolean loopContainsSwitch(LoopInfo loop, Map<BasicBlock, SwitchInfo> switchAnns) {
        for (BasicBlock b : loop.body()) {
            if (isSwitchHeader(b, switchAnns)) {
                return true;
            }
            if (b.containsSwitch()) {
                return true;
            }
        }
        return false;
    }

    /** 检查基本块是否有 switch 出边 */
    private boolean hasSwitchOutgoing(BasicBlock block, ControlFlowGraph graph) {
        return graph.outgoingOf(block).stream()
                .anyMatch(e -> e.kind() == EdgeKind.SWITCH_CASE
                        || e.kind() == EdgeKind.SWITCH_DEFAULT);
    }

    /** 检查基本块是否为 switch 目标(有 SWITCH_CASE 或 SWITCH_DEFAULT 入边) */
    private boolean isSwitchTarget(BasicBlock block, ControlFlowGraph graph) {
        return graph.incomingOf(block).stream()
                .anyMatch(e -> e.kind() == EdgeKind.SWITCH_CASE
                        || e.kind() == EdgeKind.SWITCH_DEFAULT);
    }

    /** 检查基本块是否为 switch 头(包含 switch 指令或存在于注解映射中) */
    private boolean isSwitchHeader(BasicBlock block, Map<BasicBlock, SwitchInfo> switchAnns) {
        if (switchAnns.containsKey(block)) {
            return true;
        }
        // 按 ID 匹配(折叠后块 ID 可能变化)
        for (BasicBlock key : switchAnns.keySet()) {
            if (key.id() == block.id()) {
                return true;
            }
        }
        // 按内容检测:块中是否包含 tableswitch/lookupswitch 指令
        if (block.containsSwitch()) {
            return true;
        }
        // 按起始偏移量匹配(合并后 startOffset 可能保留)
        for (BasicBlock key : switchAnns.keySet()) {
            if (key.startOffset() == block.startOffset()) {
                return true;
            }
        }
        return false;
    }

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

    /** 查找折叠操作创建的替换虚拟块(按单调计数器分配的 ID). */
    private BasicBlock findReplacementBlock(ControlFlowGraph graph) {
        if (lastCreatedVirtualId < 0) {
            return null;
        }
        for (BasicBlock b : graph.blocks()) {
            if (b.id() == lastCreatedVirtualId) {
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
