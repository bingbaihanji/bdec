package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

public final class ExpressionStatement extends Statement {

    private final Expression expression;

    public ExpressionStatement(Expression expr) {this.expression = expr;}

    public Expression expression() {return expression;}

    @Override
    public AstKind kind() {return AstKind.EXPRESSION_STMT;}

    @Override
    public List<AstNode> children() {return List.of(expression);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
