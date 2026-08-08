package com.bingbaihanji.bdec.ast;

import java.util.List;

public final class TypeDeclaration implements AstNode {

    private final int accessFlags;

    private final String simpleName;

    private final String kindName;

    private final String superName;

    private final List<String> interfaceNames;

    private final List<String> typeParameters;

    private final List<AstNode> members;

    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<String> typeParams,
                           List<AstNode> m) {
        this.accessFlags = af;
        this.simpleName = sn;
        this.kindName = kn;
        this.superName = superName;
        this.interfaceNames = List.copyOf(interfaceNames);
        this.typeParameters = List.copyOf(typeParams);
        this.members = List.copyOf(m);
    }

    /** Backward-compatible constructor without type parameters. */
    public TypeDeclaration(int af, String sn, String kn, String superName,
                           List<String> interfaceNames, List<AstNode> m) {
        this(af, sn, kn, superName, interfaceNames, List.of(), m);
    }

    public int accessFlags() {return accessFlags;}

    public String simpleName() {return simpleName;}

    public String kindName() {return kindName;}

    public String superName() {return superName;}

    public List<String> interfaceNames() {return interfaceNames;}

    public List<String> typeParameters() {return typeParameters;}

    public boolean isInterface() {return (accessFlags & 0x0200) != 0;}

    @Override
    public AstKind kind() {return AstKind.TYPE_DECLARATION;}

    @Override
    public List<AstNode> children() {return members;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}
}
