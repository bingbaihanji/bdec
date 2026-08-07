package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

public final class ReturnStatement extends Statement {

    private final Expression value;

    public ReturnStatement(Expression value) {this.value = value;}

    public Expression value() {return value;}

    @Override
    public AstKind kind() {return AstKind.RETURN;}

    @Override
    public List<AstNode> children() {return value != null ? List.of(value) : List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
