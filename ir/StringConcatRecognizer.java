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
 * 字符串拼接与装箱/拆箱识别器.
 * <p>
 * 识别并转换以下模式:
 * </p>
 * <ul>
 *   <li><b>字符串拼接</b>:{@code new StringBuilder().append(a).append(b).toString()} 转换为 {@code a + b}</li>
 *   <li><b>装箱</b>:{@code Integer.valueOf(x)} 转换为 {@code x}(自动装箱)</li>
 *   <li><b>拆箱</b>:{@code x.intValue()} 转换为 {@code x}(自动拆箱)</li>
 * </ul>
 */
public final class StringConcatRecognizer {

    /**
     * 处理一个块语句,简化字符串拼接器模式和装箱/拆箱操作.
     *
     * @param block 原始块语句
     * @return 识别后的块语句
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

    /**
     * 简化表达式,移除装箱/拆箱操作并识别字符串拼接器模式.
     */
    private Expression simplifyExpression(Expression e) {
        if (e == null) {
            return null;
        }

        // StringBuilder.append(expr) → 保留,后续模式匹配用
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
                    simplifyExpression(assign.value()),
                    assign.compoundOp());
        }
        if (e instanceof UnExpr un) {
            return new UnExpr(un.operator(), simplifyExpression(un.operand()));
        }

        return e;
    }

    /**
     * 简化常见的方法调用模式(装箱,拆箱,字符串拼接器).
     */
    private Expression simplifyInvocation(InvocationExpr inv) {
        String name = inv.methodName();

        // 装箱:Integer.valueOf(expr) → expr(自动装箱)
        if (isBoxingCall(name) && inv.arguments().size() == 1
                && inv.target() == null) {
            return inv.arguments().getFirst();
        }

        // 拆箱:expr.intValue() → expr(自动拆箱)
        if (isUnboxingCall(name) && inv.arguments().isEmpty()
                && inv.target() != null) {
            return inv.target();
        }

        // String.valueOf(expr) → "" + expr(针对非字符串类型)
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

    /**
     * 递归识别语句中的字符串拼接和装箱/拆箱模式.
     */
    private Statement recognizeStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            return recognize(bs);
        }
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(simplifyExpression(es.expression()));
        }
        return s;
    }

    /**
     * 判断方法名是否为装箱调用(如 Integer.valueOf).
     */
    private boolean isBoxingCall(String methodName) {
        return "valueOf".equals(methodName);
    }

    /**
     * 判断方法名是否为拆箱调用(如 intValue,longValue等).
     */
    private boolean isUnboxingCall(String methodName) {
        return switch (methodName) {
            case "intValue", "longValue", "floatValue", "doubleValue",
                 "shortValue", "byteValue", "charValue", "booleanValue" -> true;
            default -> false;
        };
    }

    /**
     * 判断类型是否为 java/lang/String.
     */
    private boolean isStringType(JavaType type) {
        return type.internalName() != null
                && "java/lang/String".equals(type.internalName());
    }

    /**
     * 判断表达式是否为字符串类型表达式(字面量,字符串拼接或返回String的方法调用).
     */
    private boolean isStringExpr(Expression e) {
        if (e instanceof LitExpr lit && lit.value() instanceof String) {
            return true;
        }
        if (e instanceof BinExpr bin && bin.operator() == BinaryOperator.ADD) {
            return true;
        }
        return e instanceof InvocationExpr inv && isStringType(inv.returnType());
    }
}
