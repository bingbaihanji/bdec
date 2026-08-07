package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.Expression;

import java.util.List;

public interface SwitchCase {

    List<Expression> labels();

    boolean isDefault();

    BlockStatement body();
}
