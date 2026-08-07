package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.expr.Expression;

public final class Precedence {

    public static final int LOWEST = 0;

    public static final int ASSIGNMENT = 1;

    public static final int TERNARY = 2;

    public static final int LOGICAL_OR = 3;

    public static final int LOGICAL_AND = 4;

    public static final int BITWISE_OR = 5;

    public static final int BITWISE_XOR = 6;

    public static final int BITWISE_AND = 7;

    public static final int EQUALITY = 8;

    public static final int RELATIONAL = 9;

    public static final int SHIFT = 10;

    public static final int ADDITIVE = 11;

    public static final int MULTIPLICATIVE = 12;

    public static final int UNARY = 13;

    public static final int POSTFIX = 14;

    public static final int PRIMARY = 15;

    private Precedence() {}

    public static boolean needsParentheses(Expression parent, Expression child, boolean isRight) {
        if (child.precedence() < parent.precedence()) {
            return true;
        }
        if (child.precedence() == parent.precedence() && !isRight && isRightAssociative(parent)) {
            return true;
        }
        return false;
    }

    private static boolean isRightAssociative(Expression e) {
        return e.precedence() == ASSIGNMENT || e.precedence() == TERNARY
                || e.precedence() == UNARY;
    }
}
