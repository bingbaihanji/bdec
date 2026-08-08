package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/** Method invocation expression: {@code target.method(args)} or {@code method(args)}. */
public final class InvocationExpr extends Expression {

    private final Expression target;

    private final String methodName;

    private final List<Expression> arguments;

    private final JavaType returnType;

    public InvocationExpr(Expression target, String methodName,
                          List<Expression> arguments, JavaType returnType) {
        this.target = target;
        this.methodName = methodName;
        this.arguments = List.copyOf(arguments);
        this.returnType = returnType;
    }

    public Expression target() {return target;}

    public String methodName() {return methodName;}

    public List<Expression> arguments() {return arguments;}

    public JavaType returnType() {return returnType;}

    @Override
    public AstKind kind() {return AstKind.INVOCATION;}

    @Override
    public List<AstNode> children() {
        List<AstNode> c = new ArrayList<>();
        if (target != null) {
            c.add(target);
        }
        c.addAll(arguments);
        return List.copyOf(c);
    }

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
