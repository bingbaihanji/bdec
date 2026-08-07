package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.VariableExpression;

import java.util.List;

public interface CatchClause {

    List<String> exceptionTypeNames();

    VariableExpression parameter();

    BlockStatement body();
}
