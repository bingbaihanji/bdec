package com.bingbaihanji.bdec.decompiler.ast.stmt;

import java.util.List;
import java.util.Optional;

public interface TryStatement extends Statement {

    List<ResourceDeclaration> resources();

    BlockStatement tryBlock();

    List<CatchClause> catchClauses();

    Optional<BlockStatement> finallyBlock();
}
