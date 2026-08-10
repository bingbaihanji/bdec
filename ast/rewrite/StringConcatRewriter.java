package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * 字符串拼接重写器,将字节码中的 StringBuilder 追加链和 invokedynamic 字符串拼接模式还原为 Java {@code +} 运算符表达式.
 *
 * <p>支持的模式:</p>
 * <pre>
 *   new StringBuilder().append(a).append(b).toString()  →  a + b
 *   "前缀" + a + "后缀"(经由 makeConcatWithConstants)→ "前缀" + a + "后缀"
 * </pre>
 *
 * <p>参考了 CFR 的 {@code sugarstringbuilder} 和 Vineflower 的 {@code ConcatenationHelper} 实现.</p>
 */
public class StringConcatRewriter implements RewriteRule {

    /**
     * 检查表达式是否为 invokedynamic 的 makeConcatWithConstants 调用.
     *
     * @param e 待检查的表达式
     * @return 若是 makeConcatWithConstants 调用则返回 {@code true}
     */
    private static boolean isFromIndyConcat(Expression e) {
        return e instanceof InvocationExpr inv
                && "makeConcatWithConstants".equals(inv.methodName())
                && inv.target() == null;
    }

    @Override
    public String name() {return "string-concat";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 重写类型声明中的所有方法,将方法体内的字符串拼接模式进行还原.
     *
     * @param td 待重写的类型声明
     * @return 重写后的类型声明
     */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        md.body() != null ? rewriteStatement(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /**
     * 递归重写语句,将其中出现的字符串拼接表达式进行还原.
     *
     * @param s 待重写的语句
     * @return 重写后的语句,若为孤儿拼接调用则返回 {@code null} 以过滤掉
     */
    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(this::rewriteStatement)
                    .filter(st -> st != null)
                    .toList());
        }
        if (s instanceof ExpressionStatement es) {
            Expression orig = es.expression();
            Expression rewritten = rewriteExpr(orig);
            // 单独的 makeConcatWithConstants 调用不是有效的 Java 语句,
            // 它来自 CFG 结构化过程中产生的孤立节点(如模式匹配 switch),
            // 需要将其过滤掉以避免生成的 BinExpr 引起编译错误.
            if (orig != rewritten && rewritten instanceof BinExpr
                    && isFromIndyConcat(orig)) {
                return null;
            }
            return new ExpressionStatement(rewritten);
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null ? rewriteExpr(rs.value()) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(rewriteExpr(i.condition()),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        if (s instanceof ThrowStatement ts) {
            return new ThrowStatement(rewriteExpr(ts.expression()));
        }
        return s;
    }

    /**
     * 重写表达式,将 StringBuilder 追加链和 invokedynamic 字符串拼接还原为 {@code +} 表达式.
     *
     * @param e 待重写的表达式
     * @return 重写后的表达式
     */
    private Expression rewriteExpr(Expression e) {
        // 模式1:StringBuilder 追加链 → new StringBuilder().append(a).append(b).toString()
        if (e instanceof InvocationExpr inv && "toString".equals(inv.methodName())
                && inv.arguments().isEmpty() && inv.target() != null) {
            Expression chain = unwindStringBuilder(inv.target());
            if (chain != null) {
                return chain;
            }
        }

        // 模式2:InvokeDynamic 字符串拼接 → makeConcatWithConstants(arg1, arg2, ...)
        if (e instanceof InvocationExpr inv && "makeConcatWithConstants".equals(inv.methodName())) {
            return buildConcatExpr(inv.arguments());
        }

        // 递归重写 InvocationExpr 的子表达式
        if (e instanceof InvocationExpr inv) {
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            return new InvocationExpr(
                    inv.target() != null ? rewriteExpr(inv.target()) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }

        return e;
    }

    /**
     * 将 StringBuilder.append() 调用链展开为表达式列表.
     * <p>
     * 从最内层的 append 调用开始向上回溯,收集所有的参数表达式,
     * 根节点应为 {@code new StringBuilder()} 构造调用.
     * </p>
     *
     * @param e 当前表达式(从 toString() 的 target 开始)
     * @return 展开后的拼接表达式,若非 StringBuilder 模式则返回 {@code null}
     */
    private Expression unwindStringBuilder(Expression e) {
        List<Expression> parts = new ArrayList<>();
        Expression current = e;
        while (current instanceof InvocationExpr inv
                && "append".equals(inv.methodName())
                && inv.arguments().size() == 1) {
            parts.addFirst(rewriteExpr(inv.arguments().getFirst()));
            current = inv.target();
        }
        // 根节点应为 new StringBuilder() 构造调用
        if (current instanceof InvocationExpr inv
                && "append".equals(inv.methodName()) && inv.target() instanceof NewExpr ne
                && ne.instantiatedType().internalName() != null
                && ne.instantiatedType().internalName().contains("StringBuilder")) {
            if (inv.arguments().size() == 1) {
                parts.addFirst(rewriteExpr(inv.arguments().getFirst()));
            }
        } else if (!(current instanceof NewExpr)) {
            // 非 StringBuilder 模式,无法还原
            return null;
        }
        return buildConcatExpr(parts);
    }

    /**
     * 检查表达式是否已经产生一个与 String 兼容的值.
     *
     * @param e 待检查的表达式
     * @return 若表达式为字符串字面量或 toString() 调用则返回 {@code true}
     */
    private boolean looksLikeString(Expression e) {
        if (e instanceof LitExpr lit && lit.value() instanceof String) {
            return true;
        }
        if (e instanceof InvocationExpr inv && "toString".equals(inv.methodName())) {
            return true;
        }
        return false;
    }

    /**
     * 从表达式列表构建 {@code +} 拼接链.
     * <p>
     * 确保第一个操作数为 String 类型,以满足 Java 的字符串拼接提升规则,
     * 从而使整个表达式链产生 String 类型的结果.
     * </p>
     *
     * @param parts 待拼接的表达式列表
     * @return 拼接后的表达式
     */
    private Expression buildConcatExpr(List<Expression> parts) {
        if (parts.isEmpty()) {
            return new LitExpr("", JavaType.classType("java/lang/String"));
        }
        if (parts.size() == 1) {
            // 单个元素:确保与 String 类型兼容
            Expression single = parts.get(0);
            if (looksLikeString(single)) {
                return single;
            }
            return new BinExpr(BinaryOperator.ADD,
                    new LitExpr("", JavaType.classType("java/lang/String")), single);
        }
        // 确保第一个元素为 String 类型以触发 Java 的 + 字符串拼接
        Expression first = parts.get(0);
        if (!looksLikeString(first)) {
            parts = new ArrayList<>(parts);
            parts.add(0, new LitExpr("", JavaType.classType("java/lang/String")));
        }
        Expression result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = new BinExpr(BinaryOperator.ADD, result, parts.get(i));
        }
        return result;
    }
}
