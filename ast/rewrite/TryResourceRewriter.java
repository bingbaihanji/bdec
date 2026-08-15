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
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;

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
                    ts.finallyBody() != null ? rewriteBlock(ts.finallyBody()) : null,
                    ts.resources());
        }
        return stmt;
    }

    /**
     * 在代码块中查找 try-finally 前声明的资源变量,
     * 若其 close() 在 finally 体中被调用,则转换为 try-with-resources.
     *
     * <p>javac 对 {@code try (var r = new Resource(...))} 的去糖化会生成:</p>
     * <pre>
     *   ResourceType r = new Resource(...);
     *   try { ... } finally { r.close(); }
     * </pre>
     * 本方法识别该模式并把资源声明并入 {@code try (...)}.
     *
     * @param bs 待检测的代码块
     * @return 转换后的代码块
     */
    private Statement detectTryResource(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        for (int i = 0; i < stmts.size() - 1; i++) {
            // 查找资源声明:Type r = new Resource(...) 或 r = new Resource(...)
            TryStatement.Resource res = asResourceDeclaration(stmts.get(i), stmts, i);
            if (res == null) {
                continue;
            }

            // 查找紧随其后的 try-finally
            if (!(stmts.get(i + 1) instanceof TryStatement ts)) {
                continue;
            }
            if (ts.finallyBody() == null) {
                // 方法调用资源形态:javac 对工厂返回的资源发射 if (r != null) r.close()
                //(new 资源无此守卫),结构化后 close 落 try 体尾部 + 空 catch(Throwable)
                // 而非 finally——TryTranslator 的 finally 副本识别因序列不匹配未命中.
                // 识别该形态并还原 try-with-resources.
                if (!isNullGuardedCloseShape(ts, res.varName())) {
                    continue;
                }
                Statement newTryBody = removeCloseFromBody(ts.tryBody(), res.varName());
                List<TryStatement.Resource> resources = new ArrayList<>(ts.resources());
                resources.add(0, res);
                stmts.remove(i + 1);
                stmts.remove(i);
                TryStatement newTry = foldInnerResources(
                        new TryStatement(newTryBody, List.of(), null, resources));
                stmts.add(i, newTry);
                return new BlockStatement(stmts);
            }

            // 检查 finally 体中是否包含对该变量的 close() 调用
            if (!finallyContainsClose(ts.finallyBody(), res.varName())) {
                continue;
            }

            // 重建 try 体与资源列表
            Statement newTryBody = rewriteBlock(ts.tryBody());
            List<TryStatement.CatchClause> catchClauses = ts.catchClauses();
            Statement newFinally = removeCloseFromFinally(ts.finallyBody(), res.varName());

            // 保留已折叠的内层资源,外层资源置于列表首位(声明序:外层在前)
            List<TryStatement.Resource> resources = new ArrayList<>(ts.resources());
            resources.add(0, res);

            // 移除原变量声明和旧 try,插入新的 try-with-resources
            stmts.remove(i + 1);
            stmts.remove(i);

            TryStatement newTry = foldInnerResources(
                    new TryStatement(newTryBody, catchClauses, newFinally, resources));
            stmts.add(i, newTry);
            return new BlockStatement(stmts);
        }
        return bs;
    }

    /**
     * 识别资源声明语句:Type r = new Resource(...) 或 r = new Resource(...).
     *
     * @param s     待识别的语句
     * @param stmts 语句所在列表(用于赋值形式回查声明类型)
     * @param idx   语句在列表中的下标
     * @return 识别出的资源声明,非资源声明返回 {@code null}
     */
    private TryStatement.Resource asResourceDeclaration(Statement s,
                                                        List<Statement> stmts, int idx) {
        JavaType type = null;
        String varName = null;
        Expression initExpr = null;
        if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            type = vd.type();
            varName = vd.name();
            initExpr = vd.initializer();
        } else if (s instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.target() instanceof VarExpr vx) {
            varName = vx.name();
            initExpr = assign.value();
            // 赋值形式需要回查声明处的类型
            type = findDeclaredType(stmts, varName, idx);
        }
        if (varName == null || type == null) {
            return null;
        }
        return new TryStatement.Resource(type, varName, initExpr);
    }

    /**
     * 折叠 try 体内的资源声明(多资源场景).
     *
     * <p>javac 对多资源 {@code try (a; b)} 的去糖化是嵌套的:
     * {@code a = ...; try { b = ...; try { body } finally { b.close() } } finally { a.close() }}.
     * 结构化为 AST 后内层 finally 被扁平化为 try 体内的普通 close() 语句,
     * 本方法把位于 try 体首部的资源声明连同其 close() 语句一并折叠进 resources.</p>
     *
     * @param ts 待处理的 try 语句
     * @return 折叠后的 try 语句
     */
    private TryStatement foldInnerResources(TryStatement ts) {
        if (!(ts.tryBody() instanceof BlockStatement bs)) {
            return ts;
        }
        List<Statement> stmts = new ArrayList<>(bs.statements());
        List<TryStatement.Resource> resources = new ArrayList<>(ts.resources());
        boolean changed = false;
        while (!stmts.isEmpty()) {
            TryStatement.Resource res = asResourceDeclaration(stmts.get(0), stmts, 0);
            if (res == null) {
                break;
            }
            int closeIdx = findCloseIndex(stmts, res.varName());
            if (closeIdx < 0) {
                break;
            }
            stmts.remove(closeIdx);
            stmts.remove(0);
            resources.add(res);
            changed = true;
        }
        if (!changed) {
            return ts;
        }
        return new TryStatement(new BlockStatement(stmts), ts.catchClauses(),
                ts.finallyBody(), resources);
    }

    /**
     * 在语句列表中查找对指定变量的 close() 调用语句的下标.
     *
     * @param stmts   语句列表
     * @param varName 变量名
     * @return close() 调用语句的下标,未找到返回 -1
     */
    private int findCloseIndex(List<Statement> stmts, String varName) {
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "close".equals(inv.methodName())
                    && inv.target() instanceof VarExpr vx
                    && varName.equals(vx.name())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 回查变量在本代码块中更早位置声明的类型(用于赋值形式的资源).
     *
     * @param stmts   本代码块的语句列表
     * @param varName 变量名
     * @param before  在该下标之前查找
     * @return 声明类型,未找到返回 null
     */
    private JavaType findDeclaredType(List<Statement> stmts, String varName, int before) {
        for (int j = before - 1; j >= 0; j--) {
            if (stmts.get(j) instanceof VariableDeclaration vd && varName.equals(vd.name())) {
                return vd.type();
            }
        }
        return null;
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
     * 方法调用资源形态:try 体尾部 {@code if (r != null) r.close();} + 单一空
     * catch(Throwable).javac 对工厂返回的资源发射 null 守卫 close(new 资源无),
     * 结构化后 close 落 try 体而非 finally.
     */
    private boolean isNullGuardedCloseShape(TryStatement ts, String varName) {
        if (ts.catchClauses() == null || ts.catchClauses().size() != 1) {
            return false;
        }
        var cc = ts.catchClauses().get(0);
        String ct = cc.exceptionType();
        if (ct == null || !ct.endsWith("Throwable")) {
            return false;
        }
        if (cc.body() != null
                && !(cc.body() instanceof BlockStatement eb && eb.statements().isEmpty())) {
            return false;
        }
        if (!(ts.tryBody() instanceof BlockStatement bs)
                || bs.statements().isEmpty()) {
            return false;
        }
        Statement last = bs.statements().get(bs.statements().size() - 1);
        return isNullGuardedClose(last, varName);
    }

    /** 语句是否为 {@code if (r != null) r.close();} 形态. */
    private boolean isNullGuardedClose(Statement s, String varName) {
        if (!(s instanceof IfStatement i)) {
            return false;
        }
        // then 分支是 r.close();(条件可为 r != null / !(r == null) 等)
        return isCloseCall(i.thenBranch(), varName);
    }

    /** 语句(或其单语句块)是否为 {@code r.close();} 调用. */
    private boolean isCloseCall(Statement s, String varName) {
        Statement inner = s;
        if (inner instanceof BlockStatement bs && bs.statements().size() == 1) {
            inner = bs.statements().get(0);
        }
        return inner instanceof ExpressionStatement es
                && es.expression() instanceof InvocationExpr inv
                && "close".equals(inv.methodName())
                && inv.target() instanceof VarExpr vx
                && varName.equals(vx.name());
    }

    /** 从 try 体中移除尾部的 null 守卫 close 语句. */
    private Statement removeCloseFromBody(Statement tryBody, String varName) {
        if (!(tryBody instanceof BlockStatement bs) || bs.statements().isEmpty()) {
            return tryBody;
        }
        List<Statement> stmts = new ArrayList<>(bs.statements());
        stmts.remove(stmts.size() - 1); // 移除尾部 close
        return new BlockStatement(stmts);
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
