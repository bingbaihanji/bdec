package com.bingbaihanji.bdec.ast;

import java.util.List;
import java.util.Optional;

/**
 * AST(抽象语法树)节点接口.
 * <p>
 * 所有AST节点的根接口,定义了每个节点必须实现的基本操作:
 * 获取节点类型,获取子节点,接受访问者以及获取源码位置范围.
 * 具体的节点类型包括语句(Statement)和表达式(Expression)两大类.
 * </p>
 */
public interface AstNode {

    /**
     * 获取当前节点的类型枚举值.
     *
     * @return 节点的 {@link AstKind} 类型
     */
    AstKind kind();

    /**
     * 获取当前节点的所有子节点列表.
     *
     * @return 子节点列表(不可变)
     */
    List<AstNode> children();

    /**
     * 接受访问者模式的访问.
     *
     * @param visitor 访问者实例
     * @param context 传递给访问者的上下文对象
     * @param <R>     访问者返回类型
     * @param <C>     上下文类型
     * @return 访问者处理后的结果
     */
    <R, C> R accept(AstVisitor<R, C> visitor, C context);

    /**
     * 获取当前节点在源码中的位置范围(可选).
     * 默认返回空,子类可覆盖以提供具体的源码位置信息.
     *
     * @return 包含源码范围的 {@link Optional}
     * @deprecated 默认实现恒返回 {@link Optional#empty()},且原始 Java 源码的
     *             行号与字符偏移仅凭 class 文件无法恢复,因此该接口方法的返回值
     *             不包含有效位置信息;此方法仅为兼容性保留.
     *             如需生成源码行号到字节码偏移的映射,请使用
     *             {@link com.bingbaihanji.bdec.BdecResult#sourceLineToBytecodeOffset()}
     *             (该映射仍在规划中,当前尚未填充).
     */
    @Deprecated
    default Optional<SourceRange> sourceRange() {return Optional.empty();}
}
