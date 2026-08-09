package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/** Array element access expression: {@code array[index]}.
 *  Covers both reads (a[i]) and the left-hand-side of assigns (a[i] = x). */
public final class ArrayAccessExpr extends Expression {

    private final Expression array;

    private final Expression index;

    public ArrayAccessExpr(Expression array, Expression index) {
        this.array = array;
        this.index = index;
    }

    public Expression array() {return array;}

    public Expression index() {return index;}

    @Override
    public AstKind kind() {return AstKind.ARRAY_ACCESS;}

    @Override
    public List<AstNode> children() {
        return array != null && index != null
                ? List.of(array, index) : array != null
                ? List.of(array) : List.of();
    }

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
