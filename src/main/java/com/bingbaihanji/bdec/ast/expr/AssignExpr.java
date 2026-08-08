package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/** Assignment expression. Supports both plain {@code =} and compound
 *  assignments ({@code +=}, {@code |=}, {@code >>>=}, etc.) via
 *  the optional {@link #compoundOp} field. */
public final class AssignExpr extends Expression {

    private final Expression target, value;

    private final BinaryOperator compoundOp;

    public AssignExpr(Expression t, Expression v) {
        this(t, v, null);
    }

    public AssignExpr(Expression t, Expression v, BinaryOperator compoundOp) {
        target = t;
        value = v;
        this.compoundOp = compoundOp;
    }

    public Expression target() {return target;}

    public Expression value() {return value;}

    /** null for plain {@code =}; non-null for compound like {@code |=}. */
    public BinaryOperator compoundOp() {return compoundOp;}

    @Override
    public AstKind kind() {return AstKind.ASSIGNMENT;}

    @Override
    public List<AstNode> children() {return List.of(target, value);}

    @Override
    public int precedence() {return 1;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
