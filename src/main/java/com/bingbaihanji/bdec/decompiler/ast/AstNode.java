package com.bingbaihanji.bdec.decompiler.ast;

import java.util.List;

public interface AstNode {

    AstKind kind();

    List<AstNode> children();

    <R, C> R accept(AstVisitor<R, C> visitor, C context);
}
