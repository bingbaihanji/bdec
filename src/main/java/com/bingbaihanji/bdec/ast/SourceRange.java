package com.bingbaihanji.bdec.ast;

/**
 * 源码位置范围记录类(已废弃).
 * <p>
 * 用于描述AST节点在原始Java源码中的位置信息,包括起始行号,结束行号,
 * 起始字符偏移量和结束字符偏移量.通过记录类型自动生成构造函数,
 * equals,hashCode和toString方法.
 * </p>
 * <p>
 * 这些字段描述的是原始 Java 源码中的行号与字符偏移量,仅凭 class 文件无法恢复:
 * class 文件只保留字节码指令与行号表,不包含原始源码文本及其字符偏移.
 * 当前反编译管线不会产生实际的 SourceRange 实例,{@link AstNode#sourceRange()}
 * 恒返回空 Optional.
 * </p>
 *
 * @param startLine   起始行号
 * @param endLine     结束行号
 * @param startOffset 起始字符偏移量
 * @param endOffset   结束字符偏移量
 * @deprecated 原始 Java 源码的字符偏移不可恢复,此类仅为兼容保留;
 *             如需生成源码行号到字节码偏移的映射,请使用
 *             {@link com.bingbaihanji.bdec.BdecResult#sourceLineToBytecodeOffset()}
 *             (该映射仍在规划中,当前尚未填充).
 */
@Deprecated
public record SourceRange(int startLine, int endLine, int startOffset, int endOffset) {}
