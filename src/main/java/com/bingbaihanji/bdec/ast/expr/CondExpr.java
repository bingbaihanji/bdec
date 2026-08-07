package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

public final class CondExpr extends Expression {

    private final Expression condition, trueExpr, falseExpr;

    public CondExpr(Expression c, Expression t, Expression f) {
        condition = c;
        trueExpr = t;
        falseExpr = f;
    }

    public Expression condition() {return condition;}

    public Expression trueExpr() {return trueExpr;}

    public Expression falseExpr() {return falseExpr;}

    @Override
    public AstKind kind() {return AstKind.CONDITIONAL;}

    @Override
    public List<AstNode> children() {return List.of(condition, trueExpr, falseExpr);}

    @Override
    public int precedence() {return 2;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
