package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;

public class ExpressionEmitter {

    private final IndentWriter w;

    public ExpressionEmitter(IndentWriter w) {this.w = w;}

    private static String opSymbol(BinaryOperator op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case REM -> "%";
            case EQ -> "==";
            case NE -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case AND -> "&&";
            case OR -> "||";
            case BIT_AND -> "&";
            case BIT_OR -> "|";
            case BIT_XOR -> "^";
            case SHL -> "<<";
            case SHR -> ">>";
            case USHR -> ">>>";
        };
    }

    public void emit(Expression expr) {
        switch (expr.kind()) {
            case VARIABLE -> {
                if (expr instanceof VarExpr v) {
                    w.write(v.name());
                } else {
                    w.write("var");
                }
            }
            case LITERAL -> {
                if (expr instanceof LitExpr l) {
                    emitLiteral(l);
                } else {
                    w.write("?");
                }
            }
            case BINARY -> {
                if (expr instanceof BinExpr b) {
                    emitBinary(b);
                } else {
                    w.write("(?)");
                }
            }
            case UNARY -> {
                if (expr instanceof UnExpr u) {
                    emitUnary(u);
                } else {
                    w.write("(?)");
                }
            }
            case ASSIGNMENT -> {
                if (expr instanceof AssignExpr a) {
                    emit(a.target());
                    w.write(" = ");
                    emit(a.value());
                }
            }
            case CONDITIONAL -> {
                if (expr instanceof CondExpr c) {
                    emit(c.condition());
                    w.write(" ? ");
                    emit(c.trueExpr());
                    w.write(" : ");
                    emit(c.falseExpr());
                }
            }
            case CAST -> w.write("/*cast*/");
            case NEW -> w.write("new /*type*/()");
            case INVOCATION -> w.write("/*invoke*/");
            default -> w.write("/*" + expr.kind() + "*/");
        }
    }

    private void emitLiteral(LitExpr lit) {
        Object v = lit.value();
        if (v == null) {
            w.write("null");
        } else if (v instanceof String s) {
            w.write("\"");
            w.write(s);
            w.write("\"");
        } else {
            w.write(String.valueOf(v));
        }
    }

    private void emitBinary(BinExpr bin) {
        BinaryOperator op = bin.operator();
        Expression left = bin.left(), right = bin.right();
        if (left.precedence() < bin.precedence()) {
            w.write("(");
            emit(left);
            w.write(")");
        } else {
            emit(left);
        }
        w.write(" ").write(opSymbol(op)).write(" ");
        if (right.precedence() < bin.precedence()) {
            w.write("(");
            emit(right);
            w.write(")");
        } else {
            emit(right);
        }
    }

    private void emitUnary(UnExpr un) {
        UnaryOperator op = un.operator();
        String sym = switch (op) {
            case NEG -> "-";
            case NOT -> "!";
            case COMPLEMENT -> "~";
            case PRE_INC -> "++";
            case PRE_DEC -> "--";
            case POST_INC, POST_DEC -> "";
        };
        w.write(sym);
        emit(un.operand());
        if (op == UnaryOperator.POST_INC) {
            w.write("++");
        } else if (op == UnaryOperator.POST_DEC) {
            w.write("--");
        }
    }
}
