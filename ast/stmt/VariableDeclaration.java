package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/** Local variable declaration: {@code Type name = init;}.
 *  When {@code init} is null, emits just {@code Type name;}. */
public final class VariableDeclaration extends Statement {

    private final JavaType type;

    private final String name;

    private final Expression initializer;

    public VariableDeclaration(JavaType type, String name, Expression initializer) {
        this.type = type;
        this.name = name;
        this.initializer = initializer;
    }

    public JavaType type() {return type;}

    public String name() {return name;}

    public Expression initializer() {return initializer;}

    @Override
    public AstKind kind() {return AstKind.VARIABLE_DECL;}

    @Override
    public List<AstNode> children() {
        List<AstNode> c = new ArrayList<>();
        if (initializer != null) {
            c.add(initializer);
        }
        return c;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
