package com.bingbaihanji.bdec;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 反编译结果记录类,封装反编译操作的成功/失败状态及其产出数据.
 *
 * @param success                     反编译是否成功
 * @param decompiledCode              反编译生成的 Java 源代码,失败时为 null
 * @param cause                       失败时的异常原因,成功时为 null
 * @param warnings                    反编译过程中产生的警告信息列表
 * @param sourceLineToBytecodeOffset  源代码行号到字节码偏移量的映射
 */
public record BdecResult(
        boolean success,
        String decompiledCode,
        Throwable cause,
        List<String> warnings,
        Map<Integer, Integer> sourceLineToBytecodeOffset
) {

    /**
     * 构造一个成功的反编译结果(不含警告信息).
     *
     * @param decompiledCode 反编译生成的 Java 源代码
     */
    public BdecResult(String decompiledCode) {
        this(true, decompiledCode, null, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * 构造一个失败的反编译结果.
     *
     * @param cause 失败原因异常
     * @return 包含失败信息的反编译结果
     */
    public static BdecResult error(Throwable cause) {
        return new BdecResult(false, null, cause, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * 构造一个失败的反编译结果(包含警告信息).
     *
     * @param cause    失败原因异常
     * @param warnings 警告信息列表
     * @return 包含失败信息和警告的反编译结果
     */
    public static BdecResult error(Throwable cause, List<String> warnings) {
        return new BdecResult(false, null, cause, warnings, Collections.emptyMap());
    }
}
