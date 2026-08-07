package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

public final class AssignExpr extends Expression {

    private final Expression target, value;

    public AssignExpr(Expression t, Expression v) {
        target = t;
        value = v;
    }

    public Expression target() {return target;}

    public Expression value() {return value;}

    @Override
    public AstKind kind() {return AstKind.ASSIGNMENT;}

    @Override
    public List<AstNode> children() {return List.of(target, value);}

    @Override
    public int precedence() {return 1;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
