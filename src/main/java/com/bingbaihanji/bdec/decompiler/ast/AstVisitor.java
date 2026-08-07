package com.bingbaihanji.bdec.decompiler.ast;

public interface AstVisitor<R, C> {

    R visit(AstNode node, C context);
}
