package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Set;

/**
 * try-catch 区域的结构信息记录.
 *
 * @param tryBlocks    try 块中包含的基本块集合
 * @param handlerBlock 异常处理器所在的起始基本块
 * @param catchType    捕获的异常类型(JVM 内部名称,null 表示 catch-all/finally)
 * @param startPc      try 范围起始字节码偏移
 * @param endPc        try 范围结束字节码偏移(不包含)
 */
public record TryCatchInfo(
        Set<BasicBlock> tryBlocks,
        BasicBlock handlerBlock,
        String catchType,
        int startPc,
        int endPc
) {}
