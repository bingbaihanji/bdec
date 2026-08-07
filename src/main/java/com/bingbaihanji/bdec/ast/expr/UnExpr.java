package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

public final class UnExpr extends Expression {

    private final UnaryOperator operator;

    private final Expression operand;

    public UnExpr(UnaryOperator op, Expression o) {
        operator = op;
        operand = o;
    }

    public UnaryOperator operator() {return operator;}

    public Expression operand() {return operand;}

    @Override
    public AstKind kind() {return AstKind.UNARY;}

    @Override
    public List<AstNode> children() {return List.of(operand);}

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
