package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

public final class LitExpr extends Expression {

    private final Object value;

    private final JavaType type;

    public LitExpr(Object v, JavaType t) {
        value = v;
        type = t;
    }

    public Object value() {return value;}

    @Override
    public AstKind kind() {return AstKind.LITERAL;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
