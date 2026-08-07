package com.bingbaihanji.bdec.decompiler.diagnostic;

import java.util.Map;

public record DecompilerDiagnostic(
        DiagnosticLevel level,
        String phase,
        String message,
        Throwable cause,
        Map<String, String> attributes
) {
}
