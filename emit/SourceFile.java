package com.bingbaihanji.bdec.emit;

import java.util.Map;

/**
 * 表示反编译生成的 Java 源文件.
 *
 * @param qualifiedName              类的全限定名
 * @param source                     生成的源代码文本
 * @param sourceLineToBytecodeOffset 源代码行号到字节码偏移量的映射表
 */
public record SourceFile(
        String qualifiedName,
        String source,
        Map<Integer, Integer> sourceLineToBytecodeOffset
) {}
