package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.type.JavaType;

/**
 * 表达式抽象基类.
 * <p>
 * 所有AST表达式节点的抽象父类,定义了表达式共有的属性和行为:
 * </p>
 * <ul>
 *   <li>推断类型({@link #inferredType}):保存反编译过程中推断出的表达式结果类型</li>
 *   <li>运算符优先级({@link #precedence()}):用于代码生成时确定是否需要括号</li>
 * </ul>
 */
public abstract class Expression implements AstNode {

    /** 表达式推断出的结果类型 */
    private JavaType inferredType;

    /** @return 表达式推断类型 */
    public JavaType inferredType() {return inferredType;}

    /** @param t 设置推断类型 */
    public void setInferredType(JavaType t) {this.inferredType = t;}

    /**
     * 获取表达式的运算符优先级.
     * 优先级值越大,绑定越紧密.在生成Java源码时,
     * 若子表达式的优先级低于父表达式,则需要添加括号.
     *
     * @return 运算符优先级值
     */
    public abstract int precedence();
}
