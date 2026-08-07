package com.bingbaihanji.bdec.decompiler.ast.expr;

import java.util.List;
import java.util.Optional;

public interface InvocationExpression extends Expression {

    Optional<Expression> target();

    String ownerTypeName();

    String methodName();

    String descriptor();

    List<Expression> arguments();
}
