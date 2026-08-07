package com.bingbaihanji.bdec.decompiler.ast.expr;

import com.bingbaihanji.bdec.decompiler.ast.AstNode;
import com.bingbaihanji.bdec.decompiler.type.JavaType;

public interface Expression extends AstNode {

    JavaType inferredType();

    int precedence();
}
