package com.bingbaihanji.bdec.decompiler.diagnostic;

/**
 * 诊断信息级别枚举.
 *
 * <p>按严重程度递增排列:
 * <ul>
 *   <li>{@code INFO}    —— 信息性消息(如解析进度)</li>
 *   <li>{@code WARNING} —— 非致命警告(如回退处理已触发)</li>
 *   <li>{@code ERROR}   —— 错误(如方法无法反编译)</li>
 * </ul>
 */
public enum DiagnosticLevel {
    /** 信息级别 */
    INFO,
    /** 警告级别 */
    WARNING,
    /** 错误级别 */
    ERROR
}
