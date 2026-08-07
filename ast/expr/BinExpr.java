package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

public final class BinExpr extends Expression {

    private final BinaryOperator operator;

    private final Expression left, right;

    public BinExpr(BinaryOperator op, Expression l, Expression r) {
        operator = op;
        left = l;
        right = r;
    }

    public BinaryOperator operator() {return operator;}

    public Expression left() {return left;}

    public Expression right() {return right;}

    @Override
    public AstKind kind() {return AstKind.BINARY;}

    @Override
    public List<AstNode> children() {return List.of(left, right);}

    @Override
    public int precedence() {return operator.precedence();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
