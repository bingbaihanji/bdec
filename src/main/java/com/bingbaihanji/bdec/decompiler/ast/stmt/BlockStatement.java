package com.bingbaihanji.bdec.decompiler.ast.stmt;

import java.util.List;

public interface BlockStatement extends Statement {

    List<Statement> statements();
}
