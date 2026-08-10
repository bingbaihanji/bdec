package com.bingbaihanji.bdec.cfg;

import java.util.ArrayList;
import java.util.List;

/**
 * 后支配树.
 * <p>
 * 后支配树表示控制流图中基本块之间的后支配关系.
 * 如果从块A到出口块的每条路径都必须经过块B,则称B后支配A.
 * 后支配树通过反转控制流图(交换入口和出口,反转所有边的方向)后计算支配树来构建,
 * 用于死代码消除,控制依赖分析等优化阶段.
 * </p>
 */
public final class PostDominatorTree {

    /** 基于反转控制流图计算的支配树 */
    private final DominatorTree reverseDomTree;

    /**
     * 私有构造函数.
     *
     * @param reverseDom 基于反转控制流图的支配树
     */
    private PostDominatorTree(DominatorTree reverseDom) {
        this.reverseDomTree = reverseDom;
    }

    /**
     * 计算控制流图的后支配树.
     * 通过构建反转控制流图,在其上计算支配树来实现.
     *
     * @param cfg 控制流图
     * @return 后支配树
     */
    public static PostDominatorTree compute(ControlFlowGraph cfg) {
        ReverseControlFlowGraph reverse = new ReverseControlFlowGraph(cfg);
        DominatorTree rdt = DominatorTree.compute(reverse);
        return new PostDominatorTree(rdt);
    }

    /**
     * 获取指定基本块的立即后支配者.
     *
     * @param block 基本块
     * @return 立即后支配者
     */
    public BasicBlock immediatePostDominator(BasicBlock block) {
        return reverseDomTree.idom(block);
    }

    /**
     * 判断块A是否后支配块B.
     * 即从B到出口的所有路径是否都经过A.
     *
     * @param a 可能的后支配者
     * @param b 被检查的块
     * @return 如果A后支配B则返回 {@code true}
     */
    public boolean postDominates(BasicBlock a, BasicBlock b) {
        return reverseDomTree.dominates(a, b);
    }

    /**
     * 反转控制流图.
     * <p>
     * 继承自{@link ControlFlowGraph},将原图的入口和出口对调,并反转所有控制流边的方向.
     * 这样在反转图上计算支配树即等价于在原图上计算后支配树.
     * </p>
     */
    private static class ReverseControlFlowGraph extends ControlFlowGraph {

        /**
         * 构造反转控制流图.
         * 交换入口与出口块,使用反转后的边列表.
         *
         * @param original 原始控制流图
         */
        ReverseControlFlowGraph(ControlFlowGraph original) {
            super(original.method(), original.exitBlock(), original.entryBlock(),
                    original.blocks(), buildReversedEdges(original), original.exceptionRanges());
        }

        /**
         * 构建反转后的控制流边列表.
         * 将每条边的源和目标互换.
         *
         * @param cfg 原始控制流图
         * @return 反转后的边列表
         */
        private static List<ControlFlowEdge> buildReversedEdges(ControlFlowGraph cfg) {
            List<ControlFlowEdge> reversed = new ArrayList<>();
            for (BasicBlock b : cfg.blocks()) {
                for (ControlFlowEdge e : cfg.outgoingOf(b)) {
                    // 创建新边,源和目标互换
                    // 父构造函数会将新边存入映射表,因此outgoingOf/incomingOf返回正确反转的边
                    reversed.add(new ControlFlowEdge(e.target(), e.source(),
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
            return reversed;
        }
    }
}
