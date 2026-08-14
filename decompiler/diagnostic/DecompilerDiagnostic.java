package com.bingbaihanji.bdec.decompiler.diagnostic;

/**
 * 结构化诊断信息记录.
 *
 * <p>所有字段均为固定不可变.严禁使用消息字符串携带结构化数据.
 *
 * @param level          诊断级别
 * @param phase          反编译阶段("parser" / "cfg" / "ir" / "structuring" / "ast" / "rewrite" / "emit")
 * @param className      完全限定类名,null 表示未知
 * @param methodName     方法名+描述符,null 表示未知
 * @param bytecodeOffset 字节码偏移量,-1 表示不适用
 * @param message        单行人类可读消息,无需附带位置前缀
 * @param cause          异常原因,null 表示无
 */
public record DecompilerDiagnostic(
        DiagnosticLevel level,
        String phase,
        String className,
        String methodName,
        int bytecodeOffset,
        String message,
        Throwable cause
) {

    /** 创建全局级别的信息诊断(如"类已解析") */
    public static DecompilerDiagnostic info(String phase, String className, String message) {
        return new DecompilerDiagnostic(DiagnosticLevel.INFO, phase, className, null, -1, message, null);
    }

    /** 创建方法级别的警告诊断 */
    public static DecompilerDiagnostic warning(String phase, String className,
                                               String methodName, int offset, String message) {
        return new DecompilerDiagnostic(DiagnosticLevel.WARNING, phase, className, methodName, offset, message, null);
    }

    /** 创建方法级别的错误诊断(含异常原因) */
    public static DecompilerDiagnostic error(String phase, String className, String methodName, int offset, String message, Throwable cause) {
        return new DecompilerDiagnostic(DiagnosticLevel.ERROR, phase, className, methodName, offset, message, cause);
    }
}
