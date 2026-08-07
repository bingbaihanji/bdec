package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.Expression;

import java.util.Optional;

public interface IfStatement extends Statement {

    Expression condition();

    Statement thenBranch();

    Optional<Statement> elseBranch();
}
