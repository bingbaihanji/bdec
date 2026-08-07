package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

public final class LoopStatement extends Statement {

    private final LoopKind loopKind;

    private final Expression condition;

    private final Statement body;

    public LoopStatement(LoopKind k, Expression c, Statement b) {
        loopKind = k;
        condition = c;
        body = b;
    }

    public LoopKind loopKind() {return loopKind;}

    public Expression condition() {return condition;}

    public Statement body() {return body;}

    @Override
    public AstKind kind() {return AstKind.LOOP;}

    @Override
    public List<AstNode> children() {return condition != null ? List.of(condition, body) : List.of(body);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    public enum LoopKind {
        WHILE,
        DO_WHILE,
        FOR,
        FOR_EACH
    }
}
