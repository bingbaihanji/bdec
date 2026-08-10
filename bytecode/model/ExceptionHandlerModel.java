package com.bingbaihanji.bdec.bytecode.model;

/**
 * 异常处理器模型.
 *
 * <p>描述 Java 字节码方法中 {@code exception_table} 的一条记录,
 * 即 try-catch 或 try-finally 块对应的异常处理范围与跳转目标.
 *
 * @param startPc   异常处理器覆盖的起始字节码偏移量(含)
 * @param endPc     异常处理器覆盖的结束字节码偏移量(不含)
 * @param handlerPc 异常处理器入口的字节码偏移量
 * @param catchType 捕获的异常类型内部名称,若为 {@code null} 则表示 {@code finally} 块(捕获所有异常)
 */
public record ExceptionHandlerModel(
        int startPc,
        int endPc,
        int handlerPc,
        String catchType
) {}
