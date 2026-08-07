package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

public final class IfStatement extends Statement {

    private final Expression condition;

    private final Statement thenBranch;

    private final Statement elseBranch;

    public IfStatement(Expression c, Statement t, Statement e) {
        condition = c;
        thenBranch = t;
        elseBranch = e;
    }

    public Expression condition() {return condition;}

    public Statement thenBranch() {return thenBranch;}

    public Statement elseBranch() {return elseBranch;}

    @Override
    public AstKind kind() {return AstKind.IF;}

    @Override
    public List<AstNode> children() {
        return elseBranch != null ? List.of(condition, thenBranch, elseBranch) : List.of(condition, thenBranch);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
