package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/** Object/array creation expression: {@code new Type(args)} or {@code new Type[size]}. */
public final class NewExpr extends Expression {

    private final JavaType instantiatedType;

    private final List<Expression> dimensions;

    private final List<Expression> constructorArgs;

    public NewExpr(JavaType instantiatedType, List<Expression> dimensions,
                   List<Expression> constructorArgs) {
        this.instantiatedType = instantiatedType;
        this.dimensions = List.copyOf(dimensions);
        this.constructorArgs = List.copyOf(constructorArgs);
    }

    public JavaType instantiatedType() {return instantiatedType;}

    public List<Expression> dimensions() {return dimensions;}

    public List<Expression> constructorArgs() {return constructorArgs;}

    @Override
    public AstKind kind() {return AstKind.NEW;}

    @Override
    public List<AstNode> children() {
        if (!constructorArgs.isEmpty()) {
            return List.copyOf(constructorArgs);
        }
        if (!dimensions.isEmpty()) {
            return List.copyOf(dimensions);
        }
        return List.of();
    }

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
