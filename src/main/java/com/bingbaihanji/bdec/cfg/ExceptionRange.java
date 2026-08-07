package com.bingbaihanji.bdec.cfg;

public record ExceptionRange(
        BasicBlock tryBlock,
        BasicBlock handlerBlock,
        String catchType,
        int startPc,
        int endPc
) {}
