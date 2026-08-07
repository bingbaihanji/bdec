package com.bingbaihanji.bdec.decompiler.ast.stmt;

import com.bingbaihanji.bdec.decompiler.ast.expr.Expression;
import com.bingbaihanji.bdec.decompiler.type.JavaType;

public interface ResourceDeclaration {

    JavaType type();

    String name();

    Expression initializer();
}
