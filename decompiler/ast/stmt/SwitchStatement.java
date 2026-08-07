package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.Expression;

import java.util.List;

public interface SwitchStatement extends Statement {

    Expression selector();

    List<SwitchCase> cases();
}
