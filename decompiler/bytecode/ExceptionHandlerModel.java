package com.bingbaihanji.bdec.decompiler.bytecode;

public record ExceptionHandlerModel(
        int startOffset,
        int endOffset,
        int handlerOffset,
        String catchTypeInternalName
) {
}
