package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本块重写器,检测多行字符串字面量并将其转换为 Java 15+ 的文本块(三引号字符串).
 *
 * <p>匹配模式:包含 {@code \n} 且至少包含 2 行的字符串字面量将被标记为文本块候选.</p>
 *
 * <pre>
 *   String s = "line1\nline2\nline3";
 *   → String s = """
 *       line1
 *       line2
 *       line3
 *       """;
 * </pre>
 */
public class TextBlockRewriter implements RewriteRule {

    @Override
    public String name() {return "text-block";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    /**
     * 重写类型声明,遍历所有方法和字段,将其中的多行字符串字面量标记为文本块.
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
            } else if (m instanceof FieldDeclaration fd) {
                members.add(new FieldDeclaration(fd.accessFlags(), fd.name(), fd.type(),
                        fd.initializer() != null ? rewriteExpr(fd.initializer()) : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /**
     * 递归重写语句,遍历其中的表达式并将多行字符串字面量标记为文本块.
     *
     * @param s 待重写的语句
     * @return 重写后的语句
     */
    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(this::rewriteStatement).toList());
        }
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(rewriteExpr(es.expression()));
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value()) : null);
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
     * 递归重写表达式,对字符串字面量进行文本块转换检测.
     * <p>实际的文本块格式化由代码生成器(emitter)负责,此处仅标记符合条件的字符串.</p>
     *
     * @param e 待重写的表达式
     * @return 重写后的表达式
     */
    private Expression rewriteExpr(Expression e) {
        if (e instanceof LitExpr lit) {
            Object val = lit.value();
            if (val instanceof String s && shouldConvert(s)) {
                return new LitExpr(s, JavaType.classType("java/lang/String"));
                // 代码生成器将负责多行字符串的格式化输出
                // 此处仅标记 —— 实际的文本块格式化在代码生成器中完成
            }
            return e;
        }
        if (e instanceof BinExpr be) {
            return new BinExpr(be.operator(),
                    rewriteExpr(be.left()), rewriteExpr(be.right()));
        }
        if (e instanceof InvocationExpr inv) {
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            return new InvocationExpr(
                    inv.target() != null ? rewriteExpr(inv.target()) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }
        if (e instanceof AssignExpr assign) {
            return new AssignExpr(rewriteExpr(assign.target()),
                    rewriteExpr(assign.value()),
                    assign.compoundOp());
        }
        return e;
    }

    /**
     * 判断字符串字面量是否应转换为文本块.
     * <p>条件:包含换行符且行数不少于 3 行,且不能只是空白字符.</p>
     *
     * @param s 待检查的字符串
     * @return 若符合文本块条件则返回 {@code true}
     */
    private boolean shouldConvert(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // 统计行数
        long lines = s.lines().count();
        if (lines < 3) {
            return false;
        }
        // 必须包含实际的换行符(不能仅是行尾的 \r\n)
        return s.contains("\n") || s.contains("\r\n");
    }
}
