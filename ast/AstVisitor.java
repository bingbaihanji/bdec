package com.bingbaihanji.bdec.ast;

/**
 * AST访问者接口.
 * <p>
 * 采用访问者模式(Visitor Pattern)实现对AST节点的遍历和处理.
 * 泛型参数R表示访问者操作的返回类型,C表示传递给访问者的上下文类型.
 * 默认的visit方法根据节点运行时类型自动分发到对应的visitStatement或visitExpression方法.
 * </p>
 *
 * @param <R> 访问者返回类型
 * @param <C> 上下文类型
 */
public interface AstVisitor<R, C> {

    /**
     * 访问AST节点的入口方法.
     * 根据节点的运行时类型自动分发到对应的语句或表达式访问方法.
     *
     * @param node    待访问的AST节点
     * @param context 上下文对象
     * @return 访问结果
     */
    default R visit(AstNode node, C context) {
        return switch (node) {
            case com.bingbaihanji.bdec.ast.stmt.Statement s -> visitStatement(s, context);
            case com.bingbaihanji.bdec.ast.expr.Expression e -> visitExpression(e, context);
            default -> null;
        };
    }

    /**
     * 访问语句节点.
     *
     * @param stmt    语句节点
     * @param context 上下文对象
     * @return 访问结果
     */
    R visitStatement(com.bingbaihanji.bdec.ast.stmt.Statement stmt, C context);

    /**
     * 访问表达式节点.
     *
     * @param expr    表达式节点
     * @param context 上下文对象
     * @return 访问结果
     */
    R visitExpression(com.bingbaihanji.bdec.ast.expr.Expression expr, C context);
}
