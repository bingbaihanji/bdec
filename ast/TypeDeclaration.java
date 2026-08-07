package com.bingbaihanji.bdec.ast;

import java.util.List;

public final class TypeDeclaration implements AstNode {

    private final int accessFlags;

    private final String simpleName;

    private final String kindName;

    private final List<AstNode> members;

    public TypeDeclaration(int af, String sn, String kn, List<AstNode> m) {
        accessFlags = af;
        simpleName = sn;
        kindName = kn;
        members = List.copyOf(m);
    }

    public int accessFlags() {return accessFlags;}

    public String simpleName() {return simpleName;}

    public String kindName() {return kindName;}

    @Override
    public AstKind kind() {return AstKind.TYPE_DECLARATION;}

    @Override
    public List<AstNode> children() {return members;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}
}
