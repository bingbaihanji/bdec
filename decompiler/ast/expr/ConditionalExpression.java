package com.bingbaihanji.bdec.decompiler.ast.expr;

public interface ConditionalExpression extends Expression {

    Expression condition();

    Expression trueExpression();

    Expression falseExpression();
}
