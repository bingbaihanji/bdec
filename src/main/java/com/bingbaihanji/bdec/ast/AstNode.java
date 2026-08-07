package com.bingbaihanji.bdec.ast;

import java.util.List;
import java.util.Optional;

public interface AstNode {

    AstKind kind();

    List<AstNode> children();

    <R, C> R accept(AstVisitor<R, C> visitor, C context);

    default Optional<SourceRange> sourceRange() {return Optional.empty();}
}
