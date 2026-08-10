package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstNode;

/**
 * 语句节点的抽象基类,所有具体语句类型均继承此类.
 *
 * <p>实现了 {@link AstNode} 接口,定义了语句节点在 AST 层级中的基本契约.
 */
public abstract class Statement implements AstNode {
}
