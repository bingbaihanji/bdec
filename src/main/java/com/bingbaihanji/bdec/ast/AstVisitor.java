package com.bingbaihanji.bdec.ast;

public interface AstVisitor<R, C> {

    default R visit(AstNode node, C context) {
        return switch (node) {
            case com.bingbaihanji.bdec.ast.stmt.Statement s -> visitStatement(s, context);
            case com.bingbaihanji.bdec.ast.expr.Expression e -> visitExpression(e, context);
            default -> null;
        };
    }

    R visitStatement(com.bingbaihanji.bdec.ast.stmt.Statement stmt, C context);

    R visitExpression(com.bingbaihanji.bdec.ast.expr.Expression expr, C context);
}
