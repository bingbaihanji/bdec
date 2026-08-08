package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/** Field access expression: {@code obj.field} or {@code Class.field}. */
public final class FieldAccessExpr extends Expression {

    private final Expression target;

    private final String fieldName;

    public FieldAccessExpr(Expression target, String fieldName) {
        this.target = target;
        this.fieldName = fieldName;
    }

    public Expression target() {return target;}

    public String fieldName() {return fieldName;}

    @Override
    public AstKind kind() {return AstKind.FIELD_ACCESS;}

    @Override
    public List<AstNode> children() {
        return target != null ? List.of(target) : List.of();
    }

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
