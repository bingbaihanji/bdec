package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.type.JavaType;

public abstract class Expression implements AstNode {

    private JavaType inferredType;

    public JavaType inferredType() {return inferredType;}

    public void setInferredType(JavaType t) {this.inferredType = t;}

    public abstract int precedence();
}
