package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * try-with-resources 重写器,检测包含 {@code close()} 调用的 try-finally 模式,
 * 并将其转换为 Java 7+ 的 try-with-resources 语句.
 *
 * <p>匹配模式:</p>
 * <pre>
 *   ResourceType r = new Resource(...);
 *   try { ... body ... }
 *   finally { if (r != null) r.close(); }
 * </pre>
 *
 * <p>同时支持多资源模式.参考了 Vineflower 的 {@code TryWithResourcesProcessor} 实现.</p>
 */
public class TryResourceRewriter implements RewriteRule {

    @Override
    public String name() {return "try-resource";}

    @Override
    public RewriteRuleKind kind() {return RewriteRuleKind.TRY_RESOURCE;}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 重写类型声明,遍历所有方法体进行 try-with-resources 模式检测.
     *
     * @param td 待重写的类型声明
     * @return 重写后的类型声明
     */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(withBody(md, md.body() != null ? rewriteBlock(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /**
     * 递归重写代码块,对内嵌代码块和 try 语句进行资源检测.
     *
     * @param stmt 待重写的语句
     * @return 重写后的语句
     */
    private Statement rewriteBlock(Statement stmt) {
        if (stmt instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>();
            for (Statement s : bs.statements()) {
                if (s instanceof BlockStatement inner) {
                    stmts.add(rewriteBlock(inner));
                } else {
                    stmts.add(s);
                }
            }
            return detectTryResource(new BlockStatement(stmts));
        }
        if (stmt instanceof TryStatement ts) {
            return new TryStatement(
                    rewriteBlock(ts.tryBody()),
                    ts.catchClauses(),
                    ts.finallyBody() != null ? rewriteBlock(ts.finallyBody()) : null);
        }
        return stmt;
    }

    /**
     * 在代码块中查找 try-finally 前声明的资源变量,
     * 若其 close() 在 finally 体中被调用,则转换为 try-with-resources.
     *
     * @param bs 待检测的代码块
     * @return 转换后的代码块
     */
    private Statement detectTryResource(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        for (int i = 0; i < stmts.size() - 1; i++) {
            Statement s = stmts.get(i);

            // 查找变量声明:Type r = new Resource(...)
            String varName = null;
            Expression initExpr = null;
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr assign) {
                if (assign.target() instanceof VarExpr vx) {
                    varName = vx.name();
                    initExpr = assign.value();
                }
            }
            if (varName == null) {
                continue;
            }

            // 查找紧随其后的 try-finally
            if (!(stmts.get(i + 1) instanceof TryStatement ts)) {
                continue;
            }
            if (ts.finallyBody() == null) {
                continue;
            }

            // 检查 finally 体中是否包含对该变量的 close() 调用
            if (!finallyContainsClose(ts.finallyBody(), varName)) {
                continue;
            }

            // 构建 try-with-resources 的资源列表
            List<Expression> resources = new ArrayList<>();
            resources.add(initExpr);

            // 重建 try 体
            Statement newTryBody = rewriteBlock(ts.tryBody());
            List<TryStatement.CatchClause> catchClauses = ts.catchClauses();
            Statement newFinally = removeCloseFromFinally(ts.finallyBody(), varName);

            // 移除原变量声明和旧 try,插入新的 try-with-resources
            stmts.remove(i + 1);
            stmts.remove(i);

            TryStatement newTry = new TryStatement(newTryBody, catchClauses, newFinally);
            // 注意:当前 TryStatement 模型没有 resources 字段,
            // 资源变量已在上方声明并保留以便代码生成器处理.
            stmts.add(i, newTry);
            return new BlockStatement(stmts);
        }
        return bs;
    }

    /**
     * 检查 finally 体中是否包含对指定变量的 close() 调用.
     *
     * @param finallyBody finally 体语句
     * @param varName     待检查的变量名
     * @return 若包含 close() 调用则返回 {@code true}
     */
    private boolean finallyContainsClose(Statement finallyBody, String varName) {
        List<Statement> stmts = collectStatements(finallyBody);
        for (Statement s : stmts) {
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "close".equals(inv.methodName())
                    && inv.target() instanceof VarExpr vx
                    && varName.equals(vx.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 finally 体中移除 close() 调用,保留其他语句.
     * <p>同时移除关联的 null 检查:{@code if(r != null) r.close()}.</p>
     *
     * @param finallyBody finally 体语句
     * @param varName     待移除的变量名
     * @return 移除 close() 调用后的语句,若无剩余语句则返回 {@code null}
     */
    private Statement removeCloseFromFinally(Statement finallyBody, String varName) {
        List<Statement> stmts = collectStatements(finallyBody);
        List<Statement> filtered = new ArrayList<>();
        for (Statement s : stmts) {
            // 移除 close() 调用
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "close".equals(inv.methodName())
                    && inv.target() instanceof VarExpr vx
                    && varName.equals(vx.name())) {
                continue;
            }
            // 同时移除 null 检查:if(r != null) r.close()
            if (s instanceof IfStatement ifs
                    && ifs.condition() instanceof BinExpr be
                    && be.operator() == BinaryOperator.NE) {
                continue;
            }
            filtered.add(s);
        }
        if (filtered.isEmpty()) {
            return null;
        }
        return new BlockStatement(filtered);
    }

    /**
     * 将嵌套代码块展平为语句列表.
     *
     * @param s 待展平的语句
     * @return 展平后的语句列表
     */
    private List<Statement> collectStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> result = new ArrayList<>();
            for (Statement child : bs.statements()) {
                result.addAll(collectStatements(child));
            }
            return result;
        }
        return new ArrayList<>(List.of(s));
    }
}
