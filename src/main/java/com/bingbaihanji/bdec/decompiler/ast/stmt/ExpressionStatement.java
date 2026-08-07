package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.Expression;

public interface ExpressionStatement extends Statement {

    Expression expression();
}
