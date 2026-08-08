package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/** Type cast expression: {@code (TargetType) operand}. */
public final class CastExpr extends Expression {

    private final JavaType targetType;

    private final Expression operand;

    public CastExpr(JavaType targetType, Expression operand) {
        this.targetType = targetType;
        this.operand = operand;
    }

    public JavaType targetType() {return targetType;}

    public Expression operand() {return operand;}

    @Override
    public AstKind kind() {return AstKind.CAST;}

    @Override
    public List<AstNode> children() {return List.of(operand);}

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
