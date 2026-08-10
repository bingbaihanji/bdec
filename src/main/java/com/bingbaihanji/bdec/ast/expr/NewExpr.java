package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * 对象/数组创建表达式:{@code new 类型(参数)} 或 {@code new 类型[大小]}.
 * <p>
 * 表示Java中的对象实例化(调用构造函数)和数组创建操作.
 * 通过dimensions和constructorArgs两个字段区分数组创建和对象创建.
 * </p>
 */
public final class NewExpr extends Expression {

    /** 被实例化的类型 */
    private final JavaType instantiatedType;

    /** 数组维度表达式列表(数组创建时使用) */
    private final List<Expression> dimensions;

    /** 构造函数参数列表(对象创建时使用) */
    private final List<Expression> constructorArgs;

    /**
     * 构造对象/数组创建表达式.
     *
     * @param instantiatedType 被实例化的类型
     * @param dimensions       数组维度表达式
     * @param constructorArgs  构造函数参数
     */
    public NewExpr(JavaType instantiatedType, List<Expression> dimensions,
                   List<Expression> constructorArgs) {
        this.instantiatedType = instantiatedType;
        this.dimensions = List.copyOf(dimensions);
        this.constructorArgs = List.copyOf(constructorArgs);
    }

    /** @return 被实例化的类型 */
    public JavaType instantiatedType() {return instantiatedType;}

    /** @return 数组维度列表 */
    public List<Expression> dimensions() {return dimensions;}

    /** @return 构造函数参数列表 */
    public List<Expression> constructorArgs() {return constructorArgs;}

    @Override
    public AstKind kind() {return AstKind.NEW;}

    @Override
    public List<AstNode> children() {
        if (!constructorArgs.isEmpty()) {
            return List.copyOf(constructorArgs);
        }
        if (!dimensions.isEmpty()) {
            return List.copyOf(dimensions);
        }
        return List.of();
    }

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
