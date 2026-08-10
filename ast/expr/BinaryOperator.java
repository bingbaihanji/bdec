package com.bingbaihanji.bdec.ast.expr;

/**
 * 二元运算符枚举.
 * <p>
 * 定义了Java语言中所有二元运算符类型,每个枚举常量包含对应的运算符优先级值.
 * 优先级数值越大,运算优先级越高.
 * </p>
 */
public enum BinaryOperator {
    /** 加法 + */
    ADD(11),
    /** 减法 - */
    SUB(11),
    /** 乘法 * */
    MUL(12),
    /** 除法 / */
    DIV(12),
    /** 取余 % */
    REM(12),
    /** 等于 == */
    EQ(8),
    /** 不等于 != */
    NE(8),
    /** 小于 < */
    LT(9),
    /** 大于 > */
    GT(9),
    /** 小于等于 <= */
    LE(9),
    /** 大于等于 >= */
    GE(9),
    /** 逻辑与 && */
    AND(4),
    /** 逻辑或 || */
    OR(3),
    /** 按位与 & */
    BIT_AND(7),
    /** 按位或 | */
    BIT_OR(5),
    /** 按位异或 ^ */
    BIT_XOR(6),
    /** 左移 << */
    SHL(10),
    /** 算术右移 >> */
    SHR(10),
    /** 逻辑右移 >>> */
    USHR(10),
    /** instanceof 类型判断 */
    INSTANCEOF(8);

    /** 运算符优先级(数值越大优先级越高) */
    private final int prec;

    /**
     * @param p 优先级值
     */
    BinaryOperator(int p) {prec = p;}

    /** @return 运算符优先级值 */
    public int precedence() {return prec;}
}
