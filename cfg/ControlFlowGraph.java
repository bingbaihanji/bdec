package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制流图.
 * <p>
 * 控制流图(CFG)表示一个方法内部的执行流程,由基本块(节点)和控制流边(有向边)组成.
 * 每个方法对应一个控制流图,包含唯一的入口块和出口块.
 * 提供后继/前驱基本块查询,支配树和后支配树的计算等核心功能.
 * </p>
 */
public class ControlFlowGraph {

    /** 关联的方法模型 */
    private final MethodModel method;

    /** 控制流图的入口基本块 */
    private final BasicBlock entryBlock;

    /** 控制流图的出口基本块 */
    private final BasicBlock exitBlock;

    /** 所有基本块列表(不可变) */
    private final List<BasicBlock> blocks;

    /** 异常处理范围列表(不可变) */
    private final List<ExceptionRange> exceptionRanges;

    /** 出边映射:基本块 -> 从该块出发的所有边 */
    private final Map<BasicBlock, List<ControlFlowEdge>> outgoing;

    /** 入边映射:基本块 -> 指向该块的所有边 */
    private final Map<BasicBlock, List<ControlFlowEdge>> incoming;

    /** 支配树(惰性初始化) */
    private DominatorTree dominatorTree;

    /** 后支配树(惰性初始化) */
    private PostDominatorTree postDominatorTree;

    /**
     * 构造一个控制流图.
     *
     * @param method           关联的方法模型
     * @param entryBlock       入口基本块
     * @param exitBlock        出口基本块
     * @param blocks           所有基本块列表
     * @param edges            所有控制流边列表
     * @param exceptionRanges  异常处理范围列表
     */
    public ControlFlowGraph(MethodModel method, BasicBlock entryBlock, BasicBlock exitBlock,
                            List<BasicBlock> blocks, List<ControlFlowEdge> edges,
                            List<ExceptionRange> exceptionRanges) {
        this.method = method;
        this.entryBlock = entryBlock;
        this.exitBlock = exitBlock;
        this.blocks = List.copyOf(blocks);
        this.exceptionRanges = List.copyOf(exceptionRanges);

        this.outgoing = new HashMap<>();
        this.incoming = new HashMap<>();
        for (BasicBlock b : blocks) {
            outgoing.put(b, new ArrayList<>());
            incoming.put(b, new ArrayList<>());
        }
        outgoing.put(entryBlock, new ArrayList<>());
        incoming.put(entryBlock, new ArrayList<>());
        outgoing.put(exitBlock, new ArrayList<>());
        incoming.put(exitBlock, new ArrayList<>());

        for (ControlFlowEdge edge : edges) {
            outgoing.get(edge.source()).add(edge);
            incoming.get(edge.target()).add(edge);
        }
    }

    /** @return 关联的方法模型 */
    public MethodModel method() {return method;}

    /** @return 入口基本块 */
    public BasicBlock entryBlock() {return entryBlock;}

    /** @return 出口基本块 */
    public BasicBlock exitBlock() {return exitBlock;}

    /** @return 所有基本块的不可变列表 */
    public List<BasicBlock> blocks() {return blocks;}

    /** @return 异常处理范围的不可变列表 */
    public List<ExceptionRange> exceptionRanges() {return exceptionRanges;}

    /**
     * 获取控制流图中所有边的不可变列表.
     *
     * @return 所有控制流边
     */
    public List<ControlFlowEdge> edges() {
        List<ControlFlowEdge> all = new ArrayList<>();
        for (var list : outgoing.values()) {
            all.addAll(list);
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * 获取从指定基本块出发的所有边.
     *
     * @param block 基本块
     * @return 不可变的出边列表
     */
    public List<ControlFlowEdge> outgoingOf(BasicBlock block) {
        return Collections.unmodifiableList(outgoing.getOrDefault(block, List.of()));
    }

    /**
     * 获取指向指定基本块的所有边.
     *
     * @param block 基本块
     * @return 不可变的入边列表
     */
    public List<ControlFlowEdge> incomingOf(BasicBlock block) {
        return Collections.unmodifiableList(incoming.getOrDefault(block, List.of()));
    }

    /**
     * 获取指定基本块的所有后继基本块.
     *
     * @param block 基本块
     * @return 后继基本块列表
     */
    public List<BasicBlock> successorsOf(BasicBlock block) {
        return outgoingOf(block).stream().map(ControlFlowEdge::target).toList();
    }

    /**
     * 获取指定基本块的所有前驱基本块.
     *
     * @param block 基本块
     * @return 前驱基本块列表
     */
    public List<BasicBlock> predecessorsOf(BasicBlock block) {
        return incomingOf(block).stream().map(ControlFlowEdge::source).toList();
    }

    /**
     * 获取该控制流图的支配树,惰性计算.
     *
     * @return 支配树
     */
    public DominatorTree dominatorTree() {
        if (dominatorTree == null) {
            dominatorTree = DominatorTree.compute(this);
        }
        return dominatorTree;
    }

    /**
     * 获取该控制流图的后支配树,惰性计算.
     *
     * @return 后支配树
     */
    public PostDominatorTree postDominatorTree() {
        if (postDominatorTree == null) {
            postDominatorTree = PostDominatorTree.compute(this);
        }
        return postDominatorTree;
    }

    /** @return 控制流图中基本块的数量 */
    public int blockCount() {return blocks.size();}
}
