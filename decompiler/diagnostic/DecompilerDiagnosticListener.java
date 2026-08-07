package com.bingbaihanji.bdec.decompiler.diagnostic;

import com.bingbaihanji.bdec.decompiler.ast.AstNode;
import com.bingbaihanji.bdec.decompiler.cfg.ControlFlowGraph;

public interface DecompilerDiagnosticListener {

    DecompilerDiagnosticListener NOOP = diagnostic -> {
    };

    void onDiagnostic(DecompilerDiagnostic diagnostic);

    default void onControlFlowGraph(String ownerInternalName, String methodName, ControlFlowGraph graph) {
    }

    default void onAst(String ownerInternalName, String methodName, AstNode root) {
    }
}
