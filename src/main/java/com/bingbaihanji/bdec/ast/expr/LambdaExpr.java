package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Lambda表达式和方法引用.
 * <p>
 * 同时表示lambda表达式({@code (参数) -> 函数体})和方法引用({@code 类::方法}).
 * 支持三种形式:
 * </p>
 * <ul>
 *   <li>表达式lambda:{@code x -> expr},函数体为单个表达式</li>
 *   <li>代码块lambda:{@code x -> { ... }},函数体为语句块</li>
 *   <li>方法引用:{@code Class::method}</li>
 * </ul>
 */
public final class LambdaExpr extends Expression {

    /** lambda参数列表 */
    private final List<Param> parameters;

    /** 表达式形式的lambda体:{@code x -> 表达式} */
    private final Expression bodyExpr;

    /** 代码块形式的lambda体:{@code x -> { ... }} */
    private final BlockStatement bodyBlock;

    /** 是否为方法引用(而非lambda表达式) */
    private final boolean isMethodRef;

    /** 方法引用的所有者类名(如 {@code Class::method} 中的 {@code "Class"}) */
    private final String methodRefOwner;

    /** 方法引用的方法名(如 {@code Class::method} 中的 {@code "method"}) */
    private final String methodRefName;

    /** 方法引用接收者上的 JSR-308 类型注解(JVMS 0x45/0x46,渲染于所有者前,如 {@code @A C::id}) */
    private final List<String> methodRefReceiverAnnotations;

    /** 函数式接口的返回类型 */
    private final JavaType functionalType;

    /**
     * 私有全参构造函数,由静态工厂方法调用.
     */
    private LambdaExpr(List<Param> parameters, Expression bodyExpr, BlockStatement bodyBlock,
                       boolean isMethodRef, String methodRefOwner, String methodRefName,
                       List<String> methodRefReceiverAnnotations, JavaType functionalType) {
        this.parameters = List.copyOf(parameters);
        this.bodyExpr = bodyExpr;
        this.bodyBlock = bodyBlock;
        this.isMethodRef = isMethodRef;
        this.methodRefOwner = methodRefOwner;
        this.methodRefName = methodRefName;
        this.methodRefReceiverAnnotations = methodRefReceiverAnnotations != null
                ? List.copyOf(methodRefReceiverAnnotations) : List.of();
        this.functionalType = functionalType;
    }

    /**
     * 创建表达式lambda:{@code (参数) -> 表达式}.
     *
     * @param params   参数列表
     * @param body     表达式体
     * @param funcType 函数式接口类型
     * @return Lambda表达式节点
     */
    public static LambdaExpr expression(List<Param> params, Expression body, JavaType funcType) {
        return new LambdaExpr(params, body, null, false, null, null, null, funcType);
    }

    /**
     * 创建代码块lambda:{@code (参数) -> { 语句 }}.
     *
     * @param params   参数列表
     * @param body     代码块体
     * @param funcType 函数式接口类型
     * @return Lambda表达式节点
     */
    public static LambdaExpr block(List<Param> params, BlockStatement body, JavaType funcType) {
        return new LambdaExpr(params, null, body, false, null, null, null, funcType);
    }

    /**
     * 创建方法引用:{@code 所有者::方法名}.
     *
     * @param owner    方法所有者类名
     * @param name     方法名称
     * @param funcType 函数式接口类型
     * @return Lambda表达式节点
     */
    public static LambdaExpr methodRef(String owner, String name, JavaType funcType) {
        return methodRef(owner, name, funcType, List.of());
    }

    /**
     * 创建方法引用:{@code 所有者::方法名}(含接收者类型注解).
     *
     * @param owner               方法所有者类名
     * @param name                方法名称
     * @param funcType            函数式接口类型
     * @param receiverAnnotations 接收者上的类型注解(渲染于所有者前,如 {@code @A C::id})
     * @return Lambda表达式节点
     */
    public static LambdaExpr methodRef(String owner, String name, JavaType funcType,
                                       List<String> receiverAnnotations) {
        return new LambdaExpr(List.of(), null, null, true, owner, name,
                receiverAnnotations, funcType);
    }

    /**
     * 创建占位lambda(当函数体暂无法解析时使用).
     *
     * @param params   参数列表
     * @param bodyHint 函数体提示文本
     * @param funcType 函数式接口类型
     * @return Lambda表达式节点
     */
    public static LambdaExpr placeholder(List<Param> params, String bodyHint, JavaType funcType) {
        return new LambdaExpr(params, new VarExpr(bodyHint), null, false, null, null, null, funcType);
    }

    /** @return 参数列表 */
    public List<Param> parameters() {return parameters;}

    /** @return 表达式体 */
    public Expression bodyExpr() {return bodyExpr;}

    /** @return 代码块体 */
    public BlockStatement bodyBlock() {return bodyBlock;}

    /** @return 是否为方法引用 */
    public boolean isMethodRef() {return isMethodRef;}

    /** @return 方法引用所有者类名 */
    public String methodRefOwner() {return methodRefOwner;}

    /** @return 方法引用方法名 */
    public String methodRefName() {return methodRefName;}

    /** @return 方法引用接收者上的类型注解(渲染于所有者前) */
    public List<String> methodRefReceiverAnnotations() {return methodRefReceiverAnnotations;}

    /** @return 函数式接口类型 */
    public JavaType functionalType() {return functionalType;}

    /** @return 是否为表达式体(非代码块体) */
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

    /**
     * Lambda参数记录类.
     * 包含参数名称和参数类型,用于描述lambda表达式或方法引用中的参数信息.
     *
     * @param name 参数名称
     * @param type 参数类型
     */
    public record Param(String name, JavaType type) {}
}
