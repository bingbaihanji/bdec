package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.Expression;

import java.util.Optional;

public interface LoopStatement extends Statement {

    LoopKind loopKind();

    Optional<Expression> condition();

    Statement body();
}
