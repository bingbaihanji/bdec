package com.bingbaihanji.bdec.decompiler.diagnostic;

/**
 * 诊断信息监听器接口(函数式接口).
 *
 * <p>用于接收反编译过程中产生的各类诊断事件(信息,警告,错误).
 * 实现类可将其转发到日志,UI 或收集器中.
 */
@FunctionalInterface
public interface DiagnosticListener {

    /**
     * 接收到一条诊断信息时调用.
     *
     * @param diagnostic 结构化诊断信息
     */
    void report(DecompilerDiagnostic diagnostic);
}
