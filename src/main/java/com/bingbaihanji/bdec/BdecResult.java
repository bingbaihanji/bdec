package com.bingbaihanji.bdec;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record BdecResult(
        boolean success,
        String decompiledCode,
        Throwable cause,
        List<String> warnings,
        Map<Integer, Integer> sourceLineToBytecodeOffset
) {

    public BdecResult(String decompiledCode) {
        this(true, decompiledCode, null, Collections.emptyList(), Collections.emptyMap());
    }

    public static BdecResult error(Throwable cause) {
        return new BdecResult(false, null, cause, Collections.emptyList(), Collections.emptyMap());
    }

    public static BdecResult error(Throwable cause, List<String> warnings) {
        return new BdecResult(false, null, cause, warnings, Collections.emptyMap());
    }
}
