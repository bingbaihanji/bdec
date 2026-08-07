package com.bingbaihanji.bdec.decompiler.ast.expr;

public interface VariableExpression extends Expression {

    int slot();

    int version();

    String name();
}
