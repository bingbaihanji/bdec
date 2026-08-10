package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法调用表达式:{@code 目标.方法名(参数)} 或 {@code 方法名(参数)}.
 * <p>
 * 表示Java中的方法调用操作,包括实例方法调用(如 {@code obj.toString()})
 * 和静态方法调用(如 {@code String.valueOf(x)}).当目标表达式为null时
 * 表示对当前实例的方法调用(隐式this).
 * </p>
 */
public final class InvocationExpr extends Expression {

    /** 调用目标对象表达式,静态调用或隐式this调用时为null */
    private final Expression target;

    /** 被调用的方法名称 */
    private final String methodName;

    /** 方法调用参数列表 */
    private final List<Expression> arguments;

    /** 方法返回类型 */
    private final JavaType returnType;

    /**
     * 构造方法调用表达式.
     *
     * @param target     调用目标对象(可为null)
     * @param methodName 方法名称
     * @param arguments  参数列表
     * @param returnType 返回类型
     */
    public InvocationExpr(Expression target, String methodName,
                          List<Expression> arguments, JavaType returnType) {
        this.target = target;
        this.methodName = methodName;
        this.arguments = List.copyOf(arguments);
        this.returnType = returnType;
    }

    /** @return 调用目标对象 */
    public Expression target() {return target;}

    /** @return 方法名称 */
    public String methodName() {return methodName;}

    /** @return 参数列表 */
    public List<Expression> arguments() {return arguments;}

    /** @return 返回类型 */
    public JavaType returnType() {return returnType;}

    @Override
    public AstKind kind() {return AstKind.INVOCATION;}

    @Override
    public List<AstNode> children() {
        List<AstNode> c = new ArrayList<>();
        if (target != null) {
            c.add(target);
        }
        c.addAll(arguments);
        return List.copyOf(c);
    }

    @Override
    public int precedence() {return 15;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
