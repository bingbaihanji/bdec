package com.bingbaihanji.bdec.decompiler.ast.expr;

import com.bingbaihanji.bdec.decompiler.ast.stmt.BlockStatement;

import java.util.List;

public interface LambdaExpression extends Expression {

    List<VariableExpression> parameters();

    BlockStatement body();
}
