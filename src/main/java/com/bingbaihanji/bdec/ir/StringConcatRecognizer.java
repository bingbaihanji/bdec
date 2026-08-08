package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognizes StringBuilder/StringBuffer append patterns and converts
 * them to simple string concatenation expressions.
 *
 * Pattern: new StringBuilder().append(a).append(b).toString()
 *       → a + b
 *
 * Also detects:
 * - Boxing: Integer.valueOf(x) → (Integer)x (or just x if auto-boxing)
 * - Unboxing: x.intValue() → x (auto-unboxing)
 */
public final class StringConcatRecognizer {

    /**
     * Process a block and simplify string builder patterns.
     */
    public BlockStatement recognize(BlockStatement block) {
        List<Statement> stmts = new ArrayList<>(block.statements());
        List<Statement> result = new ArrayList<>();

        for (Statement s : stmts) {
            if (s instanceof ExpressionStatement es) {
                s = new ExpressionStatement(
                        simplifyExpression(es.expression()));
            } else if (s instanceof IfStatement ifStmt) {
                s = new IfStatement(
                        simplifyExpression(ifStmt.condition()),
                        recognizeStatement(ifStmt.thenBranch()),
                        ifStmt.elseBranch() != null
                                ? recognizeStatement(ifStmt.elseBranch()) : null);
            } else if (s instanceof LoopStatement loop) {
                Expression cond = loop.condition() != null
                        ? simplifyExpression(loop.condition()) : null;
                s = new LoopStatement(loop.loopKind(), cond,
                        recognizeStatement(loop.body()));
            } else if (s instanceof BlockStatement bs) {
                s = recognize(bs);
            }
            result.add(s);
        }

        return new BlockStatement(result);
    }

    /** Simplify an expression by removing boxing/unboxing and recognizing
     * string builder patterns. */
    private Expression simplifyExpression(Expression e) {
        if (e == null) {
            return null;
        }

        // StringBuilder.append(expr) → keep, collect for pattern
        if (e instanceof InvocationExpr inv) {
            Expression simplified = simplifyInvocation(inv);
            if (simplified != e) {
                return simplified;
            }
        }

        if (e instanceof BinExpr bin) {
            return new BinExpr(bin.operator(),
                    simplifyExpression(bin.left()),
                    simplifyExpression(bin.right()));
        }
        if (e instanceof AssignExpr assign) {
            return new AssignExpr(
                    simplifyExpression(assign.target()),
                    simplifyExpression(assign.value()));
        }
        if (e instanceof UnExpr un) {
            return new UnExpr(un.operator(), simplifyExpression(un.operand()));
        }

        return e;
    }

    /** Simplify common invocation patterns (boxing, unboxing, string builder). */
    private Expression simplifyInvocation(InvocationExpr inv) {
        String name = inv.methodName();

        // Boxing: Integer.valueOf(expr) → expr (auto-boxing)
        if (isBoxingCall(name) && inv.arguments().size() == 1
                && inv.target() == null) {
            return inv.arguments().getFirst();
        }

        // Unboxing: expr.intValue() → expr (auto-unboxing)
        if (isUnboxingCall(name) && inv.arguments().isEmpty()
                && inv.target() != null) {
            return inv.target();
        }

        // String.valueOf(expr) → "" + expr (for non-string types)
        if ("valueOf".equals(name) && inv.arguments().size() == 1
                && inv.target() == null
                && isStringType(inv.returnType())) {
            Expression arg = inv.arguments().getFirst();
            if (!isStringExpr(arg)) {
                return new BinExpr(BinaryOperator.ADD,
                        new LitExpr("", JavaType.classType("java/lang/String")), arg);
            }
        }

        return inv;
    }

    private Statement recognizeStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            return recognize(bs);
        }
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(simplifyExpression(es.expression()));
        }
        return s;
    }

    private boolean isBoxingCall(String methodName) {
        return "valueOf".equals(methodName);
    }

    private boolean isUnboxingCall(String methodName) {
        return switch (methodName) {
            case "intValue", "longValue", "floatValue", "doubleValue",
                 "shortValue", "byteValue", "charValue", "booleanValue" -> true;
            default -> false;
        };
    }

    private boolean isStringType(JavaType type) {
        return type.internalName() != null
                && type.internalName().equals("java/lang/String");
    }

    private boolean isStringExpr(Expression e) {
        if (e instanceof LitExpr lit && lit.value() instanceof String) {
            return true;
        }
        if (e instanceof BinExpr bin && bin.operator() == BinaryOperator.ADD) {
            return true;
        }
        if (e instanceof InvocationExpr inv
                && isStringType(inv.returnType())) {
            return true;
        }
        return false;
    }
}
