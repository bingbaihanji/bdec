package com.bingbaihanji.bdec.ast;

import java.util.List;

public final class CompilationUnit implements AstNode {

    private final String packageName;

    private final List<String> imports;

    private final List<TypeDeclaration> types;

    public CompilationUnit(String pkg, List<String> imps, List<TypeDeclaration> ts) {
        packageName = pkg;
        imports = List.copyOf(imps);
        types = List.copyOf(ts);
    }

    public String packageName() {return packageName;}

    public List<String> imports() {return imports;}

    public List<TypeDeclaration> types() {return types;}

    @Override
    public AstKind kind() {return AstKind.COMPILATION_UNIT;}

    @Override
    public List<AstNode> children() {return List.copyOf(types);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}
}
