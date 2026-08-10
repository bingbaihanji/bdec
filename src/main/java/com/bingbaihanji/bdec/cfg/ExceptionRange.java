package com.bingbaihanji.bdec.cfg;

/**
 * 异常处理范围记录.
 * <p>
 * 表示一个try-catch块的异常处理范围,记录受保护的基本块,异常处理器基本块,
 * 捕获的异常类型以及对应的字节码偏移范围.
 * </p>
 *
 * @param tryBlock     受保护的基本块(try块)
 * @param handlerBlock 异常处理器基本块(catch块)
 * @param catchType    捕获的异常类型全限定名,{@code null} 表示finally块或catch-all
 * @param startPc      try范围的起始字节码偏移
 * @param endPc        try范围的结束字节码偏移(不包含)
 */
public record ExceptionRange(
        BasicBlock tryBlock,
        BasicBlock handlerBlock,
        String catchType,
        int startPc,
        int endPc
) {}
