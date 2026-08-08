package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/** Field declaration: {@code private static final int x = 5;}. */
public final class FieldDeclaration extends Statement {

    private final int accessFlags;

    private final String name;

    private final JavaType type;

    private final Expression initializer;

    public FieldDeclaration(int accessFlags, String name, JavaType type, Expression initializer) {
        this.accessFlags = accessFlags;
        this.name = name;
        this.type = type;
        this.initializer = initializer;
    }

    public int accessFlags() {return accessFlags;}

    public String name() {return name;}

    public JavaType type() {return type;}

    public Expression initializer() {return initializer;}

    public boolean isStatic() {return (accessFlags & 0x0008) != 0;}

    public boolean isFinal() {return (accessFlags & 0x0010) != 0;}

    @Override
    public AstKind kind() {return AstKind.FIELD_DECL;}

    @Override
    public List<AstNode> children() {
        return initializer != null ? List.of(initializer) : List.of();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
