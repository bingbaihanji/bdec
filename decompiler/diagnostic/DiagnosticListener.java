package com.bingbaihanji.bdec.decompiler.diagnostic;

@FunctionalInterface
public interface DiagnosticListener {

    void report(DecompilerDiagnostic diagnostic);
}
