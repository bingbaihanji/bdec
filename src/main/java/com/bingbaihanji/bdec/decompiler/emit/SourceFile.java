package com.bingbaihanji.bdec.decompiler.emit;

import java.util.Map;

public record SourceFile(
        String qualifiedName,
        String source,
        Map<Integer, Integer> sourceLineToBytecodeOffset
) {
}
