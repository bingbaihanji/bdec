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
 * {@code IrreducibleCFGDeobfuscator}).节点分裂(克隆多前驱块使图可归约)已尝试:
 * 分裂后图可归约,循环可结构化为 do-while,但变量生命周期错误(循环内联了
 * init 值,init 块排在循环后,重复声明)→ 静默错误输出,已回退.需更根本的
 * 方案(如 Procyon 式 labeled-goto 兜底,或重构分裂后的变量作用域).</p>
 */
public final class IrreducibleHandler {

    /**
     * 用 T1/T2 变换判定 CFG 是否可归约.
     *
     * <p>T1:删除自环;T2:合并单前驱节点.反复应用直到无可归约变换,剩余节点
     * ≤1 则可归约.仅考察常规边,异常边不参与(参照 Vineflower).</p>
     *
     * @param graph 待判定的 CFG
     * @return 可归约返回 {@code true}
     */
    public static boolean isReducible(ControlFlowGraph graph) {
        // 含异常边的图不判定为不可归约(参照 Vineflower:isStatementIrreducible
        // 对含异常后继的语句直接返回 false)——异常处理器块无常规边,会被 T2
        // 孤立为单独节点而误判不可归约,但结构化为 try-catch 是可行的.
        for (BasicBlock b : graph.blocks()) {
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() == EdgeKind.EXCEPTION) {
                    return true;
                }
            }
        }
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
                if (t.id() != b.id()) {
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
                if (preds.get(n).remove(n)) { // T1
                    succs.get(n).remove(n);
                    changed = true;
                }
                if (preds.get(n).size() == 1) { // T2
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
                    break;
                }
            }
        } while (changed);
        return remaining.size() <= 1;
    }

    /**
     * 处理不可归约的控制流图.
     *
     * @param graph 原始 CFG
     * @return 处理后的 CFG
     */
    public ControlFlowGraph handle(ControlFlowGraph graph) {
        return graph; // 透传占位:不可归约降级待后续(检测见 {@link #isReducible})
    }
}
