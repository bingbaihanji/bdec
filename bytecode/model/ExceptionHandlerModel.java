package com.bingbaihanji.bdec.bytecode.model;

public record ExceptionHandlerModel(
        int startPc,
        int endPc,
        int handlerPc,
        String catchType
) {}
