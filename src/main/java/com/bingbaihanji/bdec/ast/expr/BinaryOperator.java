package com.bingbaihanji.bdec.ast.expr;

public enum BinaryOperator {
    ADD(11),
    SUB(11),
    MUL(12),
    DIV(12),
    REM(12),
    EQ(8),
    NE(8),
    LT(9),
    GT(9),
    LE(9),
    GE(9),
    AND(4),
    OR(3),
    BIT_AND(7),
    BIT_OR(5),
    BIT_XOR(6),
    SHL(10),
    SHR(10),
    USHR(10);

    private final int prec;

    BinaryOperator(int p) {prec = p;}

    public int precedence() {return prec;}
}
