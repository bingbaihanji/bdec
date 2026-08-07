package com.bingbaihanji.bdec.decompiler.ast;

import java.util.List;

public interface TypeDeclaration extends AstNode {

    int accessFlags();

    String simpleName();

    String kindName();

    List<AstNode> members();
}
