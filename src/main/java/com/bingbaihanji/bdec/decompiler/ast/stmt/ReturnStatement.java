package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.Expression;

import java.util.Optional;

public interface ReturnStatement extends Statement {

    Optional<Expression> value();
}
