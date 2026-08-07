package com.bingbaihanji.bdec.util;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

public final class DotExporter {

    private DotExporter() {}

    public static String toDot(ControlFlowGraph cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph CFG {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  node [shape=box, fontname=\"monospace\", fontsize=10];\n");
        sb.append("  edge [fontname=\"monospace\", fontsize=8];\n\n");

        for (BasicBlock b : cfg.blocks()) {
            sb.append("  ").append(nodeId(b)).append(" [label=");
            sb.append(escapeDot(blockLabel(b)));
            sb.append("];\n");
        }

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

    private static String nodeId(BasicBlock b) {return "B" + b.id();}

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

    private static String escapeDot(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}
