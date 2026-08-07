package com.bingbaihanji.bdec.decompiler.ast;

import java.util.List;

public interface CompilationUnit extends AstNode {

    String packageName();

    List<String> imports();

    List<TypeDeclaration> types();
}
