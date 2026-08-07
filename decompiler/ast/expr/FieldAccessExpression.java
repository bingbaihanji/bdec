package com.bingbaihanji.bdec.decompiler.ast.expr;

import java.util.Optional;

public interface FieldAccessExpression extends Expression {

    Optional<Expression> target();

    String ownerTypeName();

    String fieldName();

    String descriptor();
}
