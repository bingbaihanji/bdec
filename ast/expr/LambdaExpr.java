package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/** Lambda expression: {@code (args) -> body} or method reference: {@code Class::method}. */
public final class LambdaExpr extends Expression {

    private final List<Param> parameters;

    private final Expression bodyExpr;      // expression lambda: x -> expr

    private final BlockStatement bodyBlock; // block lambda: x -> { ... }

    private final boolean isMethodRef;

    private final String methodRefOwner;    // for Class::method: "Class"

    private final String methodRefName;     // for Class::method: "method"

    private final JavaType functionalType;   // functional interface return type

    private LambdaExpr(List<Param> parameters, Expression bodyExpr, BlockStatement bodyBlock,
                       boolean isMethodRef, String methodRefOwner, String methodRefName,
                       JavaType functionalType) {
        this.parameters = List.copyOf(parameters);
        this.bodyExpr = bodyExpr;
        this.bodyBlock = bodyBlock;
        this.isMethodRef = isMethodRef;
        this.methodRefOwner = methodRefOwner;
        this.methodRefName = methodRefName;
        this.functionalType = functionalType;
    }

    /** Expression lambda: (args) -> expr */
    public static LambdaExpr expression(List<Param> params, Expression body, JavaType funcType) {
        return new LambdaExpr(params, body, null, false, null, null, funcType);
    }

    /** Block lambda: (args) -> { stmts } */
    public static LambdaExpr block(List<Param> params, BlockStatement body, JavaType funcType) {
        return new LambdaExpr(params, null, body, false, null, null, funcType);
    }

    /** Method reference: Owner::name */
    public static LambdaExpr methodRef(String owner, String name, JavaType funcType) {
        return new LambdaExpr(List.of(), null, null, true, owner, name, funcType);
    }

    /** Placeholder when body can't be resolved yet. */
    public static LambdaExpr placeholder(List<Param> params, String bodyHint, JavaType funcType) {
        return new LambdaExpr(params, new VarExpr(bodyHint), null, false, null, null, funcType);
    }

    public List<Param> parameters() {return parameters;}

    public Expression bodyExpr() {return bodyExpr;}

    public BlockStatement bodyBlock() {return bodyBlock;}

    public boolean isMethodRef() {return isMethodRef;}

    public String methodRefOwner() {return methodRefOwner;}

    public String methodRefName() {return methodRefName;}

    public JavaType functionalType() {return functionalType;}

    public boolean isExpressionBody() {return bodyExpr != null;}

    @Override
    public AstKind kind() {return AstKind.LAMBDA;}

    @Override
    public List<AstNode> children() {
        List<AstNode> c = new ArrayList<>();
        if (bodyExpr != null) {
            c.add(bodyExpr);
        }
        if (bodyBlock != null) {
            c.add(bodyBlock);
        }
        return c;
    }

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}

    /** Parameter name and type pair. */
    public record Param(String name, JavaType type) {}
}
