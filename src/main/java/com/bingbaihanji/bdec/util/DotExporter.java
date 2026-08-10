package com.bingbaihanji.bdec.util;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

/**
 * DOT 格式导出工具,将控制流图(CFG)导出为 Graphviz DOT 语言格式,
 * 便于使用 Graphviz 等工具进行可视化渲染.
 * 纯工具类,禁止实例化.
 */
public final class DotExporter {

    private DotExporter() {}

    /**
     * 将控制流图导出为 DOT 格式字符串.
     * 节点以矩形框展示基本块的指令序列,边以不同颜色和样式区分分支类型.
     *
     * @param cfg 控制流图
     * @return DOT 格式的字符串
     */
    public static String toDot(ControlFlowGraph cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph CFG {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  node [shape=box, fontname=\"monospace\", fontsize=10];\n");
        sb.append("  edge [fontname=\"monospace\", fontsize=8];\n\n");

        // 输出所有基本块节点
        for (BasicBlock b : cfg.blocks()) {
            sb.append("  ").append(nodeId(b)).append(" [label=");
            sb.append(escapeDot(blockLabel(b)));
            sb.append("];\n");
        }

        // 输出所有控制流边
        for (BasicBlock b : cfg.blocks()) {
            for (ControlFlowEdge e : cfg.outgoingOf(b)) {
                sb.append("  ").append(nodeId(e.source()));
                sb.append(" -> ").append(nodeId(e.target()));
                sb.append(" [");
                sb.append(switch (e.kind()) {
                    case TRUE_BRANCH -> "label=\"true\", color=green";
                    case FALSE_BRANCH -> "label=\"false\", color=red";
                    case GOTO -> "style=dashed";
                    case EXCEPTION -> "label=\"ex\", color=orange";
                    case SWITCH_CASE -> "label=\"case " + e.switchKey() + "\"";
                    case FALL_THROUGH -> "style=dotted";
                    default -> "";
                });
                sb.append("];\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成基本块的唯一节点标识符.
     *
     * @param b 基本块
     * @return 节点 ID 字符串(格式 B + 块 ID)
     */
    private static String nodeId(BasicBlock b) {return "B" + b.id();}

    /**
     * 生成基本块的标签文本,包含块 ID 和该块内所有指令的偏移量及助记符.
     *
     * @param b 基本块
     * @return 块标签字符串,使用 DOT 左对齐转义符分隔行
     */
    private static String blockLabel(BasicBlock b) {
        StringBuilder label = new StringBuilder();
        if (b.instructions().isEmpty()) {
            label.append("B").append(b.id());
        } else {
            label.append("B").append(b.id()).append("\\l");
            for (Instruction insn : b.instructions()) {
                label.append("  ").append(insn.offset()).append(": ")
                        .append(insn.mnemonic()).append("\\l");
            }
        }
        return label.toString();
    }

    /**
     * 对字符串进行 DOT 转义,用双引号包裹并转义内部的双引号.
     *
     * @param s 原始字符串
     * @return DOT 安全的带引号字符串
     */
    private static String escapeDot(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}
