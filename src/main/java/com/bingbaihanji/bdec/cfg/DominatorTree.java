package com.bingbaihanji.bdec.cfg;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 支配树.
 * <p>
 * 支配树表示控制流图中基本块之间的支配关系.
 * 如果从入口块到块B的每条路径都必须经过块A,则称A支配B.
 * 支配树用于SSA构造,循环检测,控制依赖分析等优化和分析阶段.
 * 当前使用迭代算法计算支配关系,未来对于超过200个块的图将切换为Lengauer-Tarjan算法.
 * </p>
 */
public final class DominatorTree {

    /** 关联的控制流图 */
    private final ControlFlowGraph cfg;

    /** 立即支配者映射:基本块 -> 其唯一的立即支配者 */
    private final Map<BasicBlock, BasicBlock> idom;

    /** 支配子节点映射:基本块 -> 被该块直接支配的所有块 */
    private final Map<BasicBlock, Set<BasicBlock>> domChildren;

    /**
     * 私有构造函数,通过静态工厂方法创建.
     *
     * @param cfg  控制流图
     * @param idom 立即支配者映射
     */
    private DominatorTree(ControlFlowGraph cfg, Map<BasicBlock, BasicBlock> idom) {
        this.cfg = cfg;
        this.idom = Collections.unmodifiableMap(idom);
        Map<BasicBlock, Set<BasicBlock>> children = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) {
            children.put(b, new HashSet<>());
        }
        for (var entry : idom.entrySet()) {
            BasicBlock child = entry.getKey();
            BasicBlock parent = entry.getValue();
            if (parent != null && child != cfg.entryBlock()) {
                children.get(parent).add(child);
            }
        }
        this.domChildren = Collections.unmodifiableMap(children);
    }

    /**
     * 使用迭代数据流算法计算支配树.
     * <p>
     * 算法:初始化入口块只支配自身,其他块支配所有块;
     * 然后迭代更新每个块的支配者集合为其所有前驱的支配者交并上自身,
     * 直到不动点.最后从支配者集合中提取立即支配者.
     * </p>
     *
     * @param cfg 控制流图
     * @return 计算出的支配树
     */
    public static DominatorTree computeIterative(ControlFlowGraph cfg) {
        List<BasicBlock> blocks = cfg.blocks();
        BasicBlock entry = cfg.entryBlock();
        Set<BasicBlock> allBlocks = new HashSet<>(blocks);

        // 初始化支配者集合
        Map<BasicBlock, Set<BasicBlock>> dom = new HashMap<>();
        for (BasicBlock b : blocks) {
            dom.put(b, b == entry ? Set.of(entry) : new HashSet<>(allBlocks));
        }

        // 迭代计算支配者直到不动点
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock b : blocks) {
                if (b == entry) {
                    continue;
                }
                Set<BasicBlock> newDom = new HashSet<>(allBlocks);
                List<BasicBlock> preds = cfg.predecessorsOf(b);
                if (preds.isEmpty()) {
                    newDom = new HashSet<>();
                    newDom.add(b);
                } else {
                    // 支配者交运算:块B的支配者为所有前驱支配者的交集加上B自身
                    for (BasicBlock pred : preds) {
                        newDom.retainAll(dom.get(pred));
                    }
                    newDom.add(b);
                }
                if (!newDom.equals(dom.get(b))) {
                    dom.put(b, newDom);
                    changed = true;
                }
            }
        }

        // 从支配者集合中提取立即支配者
        Map<BasicBlock, BasicBlock> idom = new HashMap<>();
        for (BasicBlock b : blocks) {
            if (b == entry) {
                idom.put(b, null);
                continue;
            }
            Set<BasicBlock> strictDom = new HashSet<>(dom.get(b));
            strictDom.remove(b); // 除去自身,得到严格支配者集合

            // 在严格支配者中找立即支配者:不被任何其他严格支配者支配的那个
            for (BasicBlock candidate : strictDom) {
                boolean isIdom = true;
                for (BasicBlock other : strictDom) {
                    if (!other.equals(candidate) && dom.get(other).contains(candidate)) {
                        isIdom = false;
                        break;
                    }
                }
                if (isIdom) {
                    idom.put(b, candidate);
                    break;
                }
            }
        }

        return new DominatorTree(cfg, idom);
    }

    /**
     * 计算控制流图的支配树.
     * 当前使用迭代算法,后续对于200+基本块规模的方法将切换为Lengauer-Tarjan算法.
     *
     * @param cfg 控制流图
     * @return 支配树
     */
    public static DominatorTree compute(ControlFlowGraph cfg) {
        return computeIterative(cfg); // TODO 阶段2b:对于200+基本块的方法使用Lengauer-Tarjan算法
    }

    /**
     * 判断块A是否支配块B.
     * 从B沿立即支配者链向上追溯,若遇到A则说明A支配B.
     *
     * @param a 可能的支配者
     * @param b 被检查的块
     * @return 如果A支配B则返回 {@code true}
     */
    public boolean dominates(BasicBlock a, BasicBlock b) {
        BasicBlock current = b;
        while (current != null && current != cfg.entryBlock()) {
            if (current.equals(a)) {
                return true;
            }
            current = idom.get(current);
        }
        return a == cfg.entryBlock();
    }

    /**
     * 获取指定基本块的立即支配者.
     *
     * @param block 基本块
     * @return 立即支配者,入口块返回 {@code null}
     */
    public BasicBlock idom(BasicBlock block) {return idom.get(block);}

    /**
     * 获取被指定基本块直接支配的所有子块.
     *
     * @param block 基本块
     * @return 支配子块集合
     */
    public Set<BasicBlock> children(BasicBlock block) {
        return domChildren.getOrDefault(block, Set.of());
    }

    /**
     * 计算支配边界.
     * <p>
     * 块B的支配边界是所有不在B支配下但B的某个前驱被B支配的块.
     * 支配边界是SSA构造中插入phi函数位置的关键数据结构.
     * </p>
     *
     * @return 基本块到其支配边界集合的映射
     */
    public Map<BasicBlock, Set<BasicBlock>> computeDominanceFrontier() {
        Map<BasicBlock, Set<BasicBlock>> df = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) {
            df.put(b, new HashSet<>());
        }

        for (BasicBlock b : cfg.blocks()) {
            List<BasicBlock> preds = cfg.predecessorsOf(b);
            if (preds.size() < 2) {
                continue; // 只有多前驱块才可能有非平凡的支配边界
            }
            for (BasicBlock pred : preds) {
                BasicBlock runner = pred;
                // 沿支配树向上走,直到遇到支配B的块
                while (runner != null && !dominates(runner, b)) {
                    df.get(runner).add(b);
                    runner = idom.get(runner);
                }
            }
        }
        return df;
    }
}
