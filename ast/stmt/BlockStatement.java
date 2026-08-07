package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

public final class BlockStatement extends Statement {

    private final List<Statement> statements;

    public BlockStatement(List<Statement> statements) {this.statements = List.copyOf(statements);}

    public List<Statement> statements() {return statements;}

    @Override
    public AstKind kind() {return AstKind.BLOCK;}

    @Override
    public List<AstNode> children() {return List.copyOf(statements);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
