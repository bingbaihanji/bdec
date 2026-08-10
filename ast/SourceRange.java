package com.bingbaihanji.bdec.ast;

/**
 * 源码位置范围记录类.
 * <p>
 * 用于描述AST节点在原始Java源码中的位置信息,包括起始行号,结束行号,
 * 起始字符偏移量和结束字符偏移量.通过记录类型自动生成构造函数,
 * equals,hashCode和toString方法.
 * </p>
 *
 * @param startLine   起始行号
 * @param endLine     结束行号
 * @param startOffset 起始字符偏移量
 * @param endOffset   结束字符偏移量
 */
public record SourceRange(int startLine, int endLine, int startOffset, int endOffset) {}
