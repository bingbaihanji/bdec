package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

public final class MethodDeclaration extends Statement {

    private final int accessFlags;

    private final String name;

    private final JavaType returnType;

    private final String[] parameterNames;

    private final JavaType[] parameterTypes;

    private final List<String> typeParameters;

    private final Statement body;

    public MethodDeclaration(int accessFlags, String name, JavaType returnType,
                             String[] paramNames, JavaType[] paramTypes, Statement body) {
        this(accessFlags, name, returnType, paramNames, paramTypes, List.of(), body);
    }

    public MethodDeclaration(int accessFlags, String name, JavaType returnType,
                             String[] paramNames, JavaType[] paramTypes,
                             List<String> typeParameters, Statement body) {
        this.accessFlags = accessFlags;
        this.name = name;
        this.returnType = returnType;
        this.parameterNames = paramNames;
        this.parameterTypes = paramTypes;
        this.typeParameters = List.copyOf(typeParameters);
        this.body = body;
    }

    public int accessFlags() {return accessFlags;}

    public String name() {return name;}

    public JavaType returnType() {return returnType;}

    public String[] parameterNames() {return parameterNames;}

    public JavaType[] parameterTypes() {return parameterTypes;}

    public List<String> typeParameters() {return typeParameters;}

    public Statement body() {return body;}

    public boolean isStatic() {return (accessFlags & 0x0008) != 0;}

    @Override
    public AstKind kind() {return AstKind.METHOD_DECL;}

    @Override
    public List<AstNode> children() {return body != null ? List.of(body) : List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
