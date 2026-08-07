package com.bingbaihanji.bdec.decompiler.ast.expr;

public interface UnaryExpression extends Expression {

    UnaryOperator operator();

    Expression operand();
}
