package com.bingbaihanji.bdec.decompiler.diagnostic;

/**
 * Structured diagnostic — all fields are fixed.
 * Never use the message string to carry structured data.
 */
public record DecompilerDiagnostic(
        DiagnosticLevel level,
        String phase,           // "parser" / "cfg" / "ir" / "structuring" / "ast" / "rewrite" / "emit"
        String className,       // fully qualified, null = unknown
        String methodName,      // name + descriptor, null = unknown
        int bytecodeOffset,     // -1 = not applicable
        String message,         // single-line human-readable, no location prefix needed
        Throwable cause         // null = none
) {

    /** Global-level info (e.g. class parsed) */
    public static DecompilerDiagnostic info(String phase, String className, String message) {
        return new DecompilerDiagnostic(DiagnosticLevel.INFO, phase, className, null, -1, message, null);
    }

    /** Method-level warning */
    public static DecompilerDiagnostic warning(String phase, String className,
                                               String methodName, int offset, String message) {
        return new DecompilerDiagnostic(DiagnosticLevel.WARNING, phase, className, methodName, offset, message, null);
    }

    /** Method-level error with cause */
    public static DecompilerDiagnostic error(String phase, String className,
                                             String methodName, int offset, String message, Throwable cause) {
        return new DecompilerDiagnostic(DiagnosticLevel.ERROR, phase, className, methodName, offset, message, cause);
    }
}
