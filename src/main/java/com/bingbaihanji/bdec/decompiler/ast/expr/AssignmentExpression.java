package com.bingbaihanji.bdec.decompiler.ast.expr;

public interface AssignmentExpression extends Expression {

    AssignmentOperator operator();

    Expression target();

    Expression value();
}
