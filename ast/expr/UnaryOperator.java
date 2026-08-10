package com.bingbaihanji.bdec.ast.expr;

/**
 * 一元运算符枚举.
 * <p>
 * 定义了Java语言中所有一元运算符类型,包括算术取反,逻辑非,按位取反,
 * 前置/后置自增和自减操作.
 * </p>
 */
public enum UnaryOperator {
    /** 取反 - */
    NEG,
    /** 逻辑非 ! */
    NOT,
    /** 按位取反 ~ */
    COMPLEMENT,
    /** 前置自增 ++i */
    PRE_INC,
    /** 前置自减 --i */
    PRE_DEC,
    /** 后置自增 i++ */
    POST_INC,
    /** 后置自减 i-- */
    POST_DEC
}
