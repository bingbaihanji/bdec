package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/**
 * AST node representing a {@code synchronized (expr) { body }} statement.
 */
public final class SynchronizedStatement extends Statement {

    private final Expression monitorObject;

    private final Statement body;

    public SynchronizedStatement(Expression monitorObject, Statement body) {
        this.monitorObject = monitorObject;
        this.body = body;
    }

    public Expression monitorObject() {return monitorObject;}

    public Statement body() {return body;}

    @Override
    public AstKind kind() {return AstKind.SYNCHRONIZED;}

    @Override
    public List<AstNode> children() {
        return List.of(monitorObject, body);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitStatement(this, context);
    }
}
