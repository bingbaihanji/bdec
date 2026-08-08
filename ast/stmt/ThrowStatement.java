package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/** AST node for {@code throw expr;} statements. */
public final class ThrowStatement extends Statement {

    private final Expression expression;

    public ThrowStatement(Expression expression) {
        this.expression = expression;
    }

    public Expression expression() {return expression;}

    @Override
    public AstKind kind() {return AstKind.THROW;}

    @Override
    public List<AstNode> children() {
        return expression != null ? List.of(expression) : List.of();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
