package com.bingbaihanji.bdec.decompiler.ast.expr;

public interface BinaryExpression extends Expression {

    BinaryOperator operator();

    Expression left();

    Expression right();
}
