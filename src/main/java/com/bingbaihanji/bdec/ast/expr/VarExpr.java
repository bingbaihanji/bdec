package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

public final class VarExpr extends Expression {

    private final String name;

    public VarExpr(String n) {name = n;}

    public String name() {return name;}

    @Override
    public AstKind kind() {return AstKind.VARIABLE;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
