package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 不可归约控制流图处理器.
 *
 * <p>提供 T1/T2 可归约性检测({@link #isReducible},参照 Vineflower
 * {@code IrreducibleCFGDeobfuscator.isStatementIrreducible}).节点分裂
 * (克隆多前驱块使图可归约)已尝试但回退——分裂后的循环体位于循环头内
 * (r++ 与条件同块),LoopTranslator 无法结构化,产出静默语义错误,故
 * {@link #handle} 保持透传.</p>
 */
public final class IrreducibleHandler {

    /**
     * 处理不可归约的控制流图.
     *
     * @param graph 原始 CFG
     * @return 处理后的 CFG
     */
    public ControlFlowGraph handle(ControlFlowGraph graph) {
        return graph; // 透传占位:不可归约降级待后续(检测见 {@link #isReducible})
    }

    /**
     * 用 T1/T2 变换判定 CFG 是否可归约.
     *
     * <p>T1:删除自环;T2:合并单前驱节点.反复应用直到无可归约变换,剩余节点
     * ≤1 则可归约.仅考察常规边(TRUE/FALSE/GOTO/SWITCH/FALL_THROUGH 等),
     * 异常边不参与——含异常处理器的图不作此判定(参照 Vineflower).</p>
     *
     * @param graph 待判定的 CFG
     * @return 可归约返回 {@code true}
     */
    public static boolean isReducible(ControlFlowGraph graph) {
        // 节点 → 常规前驱/后继集合(不含异常边,不含自环)
        Map<Integer, Set<Integer>> preds = new HashMap<>();
        Map<Integer, Set<Integer>> succs = new HashMap<>();
        for (BasicBlock b : graph.blocks()) {
            if (b == graph.entryBlock() || b == graph.exitBlock()) {
                continue;
            }
            preds.put(b.id(), new HashSet<>());
            succs.put(b.id(), new HashSet<>());
        }
        for (BasicBlock b : graph.blocks()) {
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() == EdgeKind.EXCEPTION) {
                    continue;
                }
                BasicBlock t = e.target();
                if (t == graph.entryBlock() || t == graph.exitBlock()
                        || !preds.containsKey(t.id()) || !preds.containsKey(b.id())) {
                    continue;
                }
                if (t.id() != b.id()) { // 自环由 T1 删除,不参与前驱计数
                    preds.get(t.id()).add(b.id());
                    succs.get(b.id()).add(t.id());
                }
            }
        }

        Set<Integer> remaining = new HashSet<>(preds.keySet());
        boolean changed;
        do {
            changed = false;
            for (Integer n : new ArrayList<>(remaining)) {
                if (!remaining.contains(n)) {
                    continue;
                }
                // T1:删除自环
                if (preds.get(n).remove(n)) {
                    succs.get(n).remove(n);
                    changed = true;
                }
                // T2:单前驱节点合并进其唯一前驱
                if (preds.get(n).size() == 1) {
                    Integer p = preds.get(n).iterator().next();
                    remaining.remove(n);
                    for (Integer s : succs.get(n)) {
                        if (!remaining.contains(s)) {
                            continue;
                        }
                        preds.get(s).remove(n);
                        preds.get(s).add(p);
                        succs.get(p).add(s);
                    }
                    changed = true;
                    break; // 图已变更,重扫
                }
            }
        } while (changed);
        return remaining.size() <= 1;
    }
}
