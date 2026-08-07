package com.bingbaihanji.bdec.decompiler;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 反编译结果封装
 * @param lineNumberMapping  [源码行号 -> 字节码偏移量] 映射，便于 Debugger
 */
public record DecompileResult(
        boolean success,
        String decompiledCode,
        Throwable cause,
        List<String> warnings,
        Map<Integer, Integer> lineNumberMapping

) {

    public DecompileResult(String decompiledCode) {
        this(true, decompiledCode, null, Collections.emptyList(), Collections.emptyMap());
    }

    public static DecompileResult error(Throwable cause) {
        return new DecompileResult(false, null, cause, Collections.emptyList(), Collections.emptyMap());
    }
}