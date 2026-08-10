package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.expr.Expression;

/**
 * 表达式运算符优先级常量定义及括号判据工具类.
 * 优先级数值越大表示优先级越高(绑定越紧密).
 */
public final class Precedence {

    /** 最低优先级(无表达式的默认值) */
    public static final int LOWEST = 0;

    /** 赋值运算符优先级(=,+= 等) */
    public static final int ASSIGNMENT = 1;

    /** 三元条件运算符优先级(? :) */
    public static final int TERNARY = 2;

    /** 逻辑或优先级(||) */
    public static final int LOGICAL_OR = 3;

    /** 逻辑与优先级(&&) */
    public static final int LOGICAL_AND = 4;

    /** 位或优先级(|) */
    public static final int BITWISE_OR = 5;

    /** 位异或优先级(^) */
    public static final int BITWISE_XOR = 6;

    /** 位与优先级(&) */
    public static final int BITWISE_AND = 7;

    /** 相等性比较优先级(==, !=) */
    public static final int EQUALITY = 8;

    /** 关系比较优先级(<, >, <=, >=, instanceof) */
    public static final int RELATIONAL = 9;

    /** 位移优先级(<<, >>, >>>) */
    public static final int SHIFT = 10;

    /** 加减优先级(+, -) */
    public static final int ADDITIVE = 11;

    /** 乘除取模优先级(*, /, %) */
    public static final int MULTIPLICATIVE = 12;

    /** 一元运算符优先级(+, -, !, ~, ++, --) */
    public static final int UNARY = 13;

    /** 后缀运算符优先级(expr++, expr--) */
    public static final int POSTFIX = 14;

    /** 最高优先级(字面量,变量,方法调用等基本表达式) */
    public static final int PRIMARY = 15;

    private Precedence() {}

    /**
     * 判断子表达式是否需要添加括号.
     *
     * @param parent  父表达式节点
     * @param child   子表达式节点
     * @param isRight 该子表达式是否位于父表达式的右侧
     * @return 需要括号返回 true,否则返回 false
     */
    public static boolean needsParentheses(Expression parent, Expression child, boolean isRight) {
        if (child.precedence() < parent.precedence()) {
            return true;
        }
        // 右结合运算符(赋值,三元,一元)在相同优先级的左侧也需要括号
        if (child.precedence() == parent.precedence() && !isRight && isRightAssociative(parent)) {
            return true;
        }
        return false;
    }

    /**
     * 判断表达式是否为右结合运算符.
     * 右结合运算符包括赋值类,三元条件,一元运算符.
     *
     * @param e 表达式节点
     * @return 右结合返回 true
     */
    private static boolean isRightAssociative(Expression e) {
        return e.precedence() == ASSIGNMENT || e.precedence() == TERNARY
                || e.precedence() == UNARY;
    }
}
