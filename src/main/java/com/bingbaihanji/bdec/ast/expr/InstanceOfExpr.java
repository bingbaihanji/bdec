package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/** {@code obj instanceof Type} expression. */
public final class InstanceOfExpr extends Expression {

    private final Expression operand;

    private final JavaType targetType;

    public InstanceOfExpr(Expression operand, JavaType targetType) {
        this.operand = operand;
        this.targetType = targetType;
    }

    public Expression operand() {return operand;}

    public JavaType targetType() {return targetType;}

    @Override
    public AstKind kind() {return AstKind.INSTANCE_OF;}

    @Override
    public List<AstNode> children() {
        return operand != null ? List.of(operand) : List.of();
    }

    @Override
    public int precedence() {return 8;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
