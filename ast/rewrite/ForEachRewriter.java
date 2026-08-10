package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * 增强 for-each 循环重写器,检测基于 Iterator 的循环模式,
 * 将其转换为 Java 的 {@code for (E element : collection)} 增强 for-each 循环.
 *
 * <p>可识别的模式:
 * <pre>
 *   Iterator iter = collection.iterator();
 *   while (iter.hasNext()) { E element = iter.next(); ...body... }
 *
 *   → for (E element : collection) { ...body... }
 * </pre>
 *
 * <p>设计参考 CFR 的 {@code IterLoopRewriter}.
 */
public class ForEachRewriter implements RewriteRule {

    @Override
    public String name() {return "for-each";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /** 递归重写类型声明中的每个方法体 */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        md.body() != null ? rewriteBlock(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /** 递归重写语句块,检测 for-each 模式 */
    private Statement rewriteBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement child : bs.statements()) {
                rewritten.add(rewriteBlock(child));
            }
            return detectForEach(new BlockStatement(rewritten));
        }
        if (s instanceof LoopStatement ls) {
            return new LoopStatement(ls.loopKind(), ls.initExpr(),
                    ls.condition(), ls.incrExpr(), rewriteBlock(ls.body()));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteBlock(i.thenBranch()),
                    i.elseBranch() != null ? rewriteBlock(i.elseBranch()) : null);
        }
        return s;
    }

    /**
     * 遍历代码块中的语句,查找相邻的 Iterator 声明 + while 循环模式,
     * 将其合并为增强 for-each 循环.
     */
    private Statement detectForEach(BlockStatement bs) {
        // 先展开嵌套的 BlockStatement(CFG 分组可能产生不必要的块作用域,
        // 例如将循环前导语句包装在单独的 { } 块中)
        List<Statement> stmts = new ArrayList<>();
        for (Statement s : bs.statements()) {
            if (s instanceof BlockStatement inner) {
                stmts.addAll(inner.statements());
            } else {
                stmts.add(s);
            }
        }
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < stmts.size() - 1; i++) {
                Statement s = stmts.get(i);
                // 查找:包含 iterator() 方法调用的表达式语句
                ForEachCandidate candidate = matchIteratorDecl(s);
                if (candidate == null) {
                    continue;
                }

                // 验证下一条语句为 while 循环
                if (!(stmts.get(i + 1) instanceof LoopStatement loop)
                        || loop.loopKind() != LoopStatement.LoopKind.WHILE) {
                    continue;
                }

                // 匹配:while(iter.hasNext())
                ForEachCandidate result = matchWhileLoop(loop, candidate);
                if (result == null) {
                    continue;
                }

                // 构建增强 for-each 循环
                LoopStatement forEach = new LoopStatement(
                        LoopStatement.LoopKind.FOR_EACH,
                        result.elementVar,
                        candidate.iterableExpr,
                        result.body);

                // 用增强 for-each 循环替换 Iterator 声明和 while 循环
                stmts.remove(i + 1);
                stmts.remove(i);
                stmts.add(i, forEach);
                changed = true;
                break;
            }
        } while (changed);

        return new BlockStatement(stmts);
    }

    /** 匹配:{@code Iterator iter = collection.iterator();}
     *  支持两种形式:ExpressionStatement(AssignExpr) 和 VariableDeclaration. */
    private ForEachCandidate matchIteratorDecl(Statement s) {
        String varName;
        Expression iterableExpr;

        // 形式1:VariableDeclaration——BlockReducer 输出
        //   Iterator var2 = items.iterator()
        if (s instanceof VariableDeclaration vd
                && vd.initializer() instanceof InvocationExpr inv
                && "iterator".equals(inv.methodName())
                && inv.target() != null) {
            varName = vd.name();
            iterableExpr = inv.target();
            return new ForEachCandidate(varName, iterableExpr);
        }

        // 形式2:ExpressionStatement(AssignExpr)——旧的模式
        if (!(s instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.value() instanceof InvocationExpr inv2)) {
            return null;
        }
        if (!"iterator".equals(inv2.methodName())) {
            return null;
        }
        if (inv2.target() == null) {
            return null;
        }

        // 提取迭代器变量名
        if (assign.target() instanceof VarExpr vx) {
            varName = vx.name();
        } else {
            return null;
        }

        return new ForEachCandidate(varName, inv2.target());
    }

    /** 匹配:{@code while(iter.hasNext()) { E e = iter.next(); ... }} */
    private ForEachCandidate matchWhileLoop(LoopStatement loop, ForEachCandidate candidate) {
        // 检查循环条件:iter.hasNext()
        if (!(loop.condition() instanceof InvocationExpr condInv)) {
            return null;
        }
        if (!"hasNext".equals(condInv.methodName())) {
            return null;
        }
        if (!(condInv.target() instanceof VarExpr var)) {
            return null;
        }
        if (!candidate.iterVar.equals(var.name())) {
            return null;
        }

        // 检查循环体首条语句:E element = iter.next()
        List<Statement> bodyStmts = getBodyStatements(loop.body());
        if (bodyStmts.isEmpty()) {
            return null;
        }

        Statement first = bodyStmts.get(0);
        Expression elementVar;

        // 形式1:VariableDeclaration——如 "String item = (String) iter.next()"
        if (first instanceof VariableDeclaration vd
                && vd.initializer() instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr nextVar
                && candidate.iterVar.equals(nextVar.name())) {
            elementVar = new VarExpr(vd.name());
        }
        // 形式2:ExpressionStatement(AssignExpr)——如 "item = iter.next()"
        else if (first instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.value() instanceof InvocationExpr inv2
                && "next".equals(inv2.methodName())
                && inv2.target() instanceof VarExpr nextVar2
                && candidate.iterVar.equals(nextVar2.name())) {
            elementVar = assign.target();
        } else {
            // 形式3:next() 被内联到表达式中——如 "println(iter.next())"
            // 查找循环体中所有 iterVar.next() 调用并替换为元素变量
            String elementName = "element";
            if (!containsNextCall(loop.body(), candidate.iterVar)) {
                return null;
            }
            elementVar = new VarExpr(elementName);
        }

        // 构建新循环体(将 iterVar.next() 调用替换为元素变量引用,
        // 若首个语句为 next() 赋值则同时移除该语句)
        List<Statement> newBodyStmts = new ArrayList<>(bodyStmts);
        // 若首条语句是 next() 赋值,则移除它(已提取为 for-each 元素变量)
        boolean firstIsNextAssign = (first instanceof VariableDeclaration vd
                && vd.initializer() instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr nv
                && candidate.iterVar.equals(nv.name()))
                || (first instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.value() instanceof InvocationExpr inv2
                && "next".equals(inv2.methodName())
                && inv2.target() instanceof VarExpr nv2
                && candidate.iterVar.equals(nv2.name()));
        if (firstIsNextAssign) {
            newBodyStmts.remove(0);
        }
        // 将剩余语句中的 iterVar.next() 替换为元素变量
        List<Statement> replacedStmts = new ArrayList<>();
        for (Statement stmt : newBodyStmts) {
            replacedStmts.add(replaceNextCalls(stmt, candidate.iterVar,
                    (VarExpr) elementVar));
        }
        newBodyStmts = replacedStmts;
        Statement newBody;
        if (newBodyStmts.isEmpty()) {
            newBody = new BlockStatement(List.of());
        } else if (newBodyStmts.size() == 1) {
            newBody = newBodyStmts.get(0);
        } else {
            newBody = new BlockStatement(newBodyStmts);
        }

        return new ForEachCandidate(candidate.iterVar, candidate.iterableExpr,
                elementVar, newBody);
    }

    /** 提取语句中的子语句列表(若为块语句则展开,否则包装为单元素列表) */
    private List<Statement> getBodyStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new ArrayList<>(bs.statements());
        }
        return new ArrayList<>(List.of(s));
    }

    /** 检查语句树中是否包含 {@code iterVar.next()} 调用 */
    private boolean containsNextCall(Statement s, String iterVar) {
        if (s instanceof ExpressionStatement es) {
            return containsNextCallInExpr(es.expression(), iterVar);
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream()
                    .anyMatch(child -> containsNextCall(child, iterVar));
        }
        if (s instanceof LoopStatement ls) {
            return containsNextCall(ls.body(), iterVar);
        }
        if (s instanceof IfStatement i) {
            return containsNextCall(i.thenBranch(), iterVar)
                    || (i.elseBranch() != null && containsNextCall(i.elseBranch(), iterVar));
        }
        return false;
    }

    /** 在表达式中查找 iterVar.next() */
    private boolean containsNextCallInExpr(Expression e, String iterVar) {
        if (e instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr v
                && iterVar.equals(v.name())) {
            return true;
        }
        if (e instanceof InvocationExpr inv) {
            if (inv.target() != null && containsNextCallInExpr(inv.target(), iterVar)) {
                return true;
            }
            return inv.arguments().stream()
                    .anyMatch(a -> containsNextCallInExpr(a, iterVar));
        }
        if (e instanceof BinExpr bin) {
            return containsNextCallInExpr(bin.left(), iterVar)
                    || containsNextCallInExpr(bin.right(), iterVar);
        }
        if (e instanceof UnExpr un) {
            return containsNextCallInExpr(un.operand(), iterVar);
        }
        if (e instanceof CastExpr cast) {
            return containsNextCallInExpr(cast.operand(), iterVar);
        }
        return false;
    }

    /** 将语句中的 {@code iterVar.next()} 调用替换为 {@code replacement} */
    private Statement replaceNextCalls(Statement s, String iterVar, VarExpr replacement) {
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(
                    replaceNextInExpr(es.expression(), iterVar, replacement));
        }
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(child -> replaceNextCalls(child, iterVar, replacement))
                    .toList());
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? replaceNextInExpr(rs.value(), iterVar, replacement) : null);
        }
        if (s instanceof LoopStatement ls) {
            return new LoopStatement(ls.loopKind(), ls.initExpr(),
                    ls.condition(), ls.incrExpr(),
                    replaceNextCalls(ls.body(), iterVar, replacement));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(
                    i.condition() != null
                            ? replaceNextInExpr(i.condition(), iterVar, replacement) : null,
                    replaceNextCalls(i.thenBranch(), iterVar, replacement),
                    i.elseBranch() != null
                            ? replaceNextCalls(i.elseBranch(), iterVar, replacement) : null);
        }
        return s;
    }

    /** 将表达式中的 {@code iterVar.next()} 替换为 {@code replacement} */
    private Expression replaceNextInExpr(Expression e, String iterVar, VarExpr replacement) {
        if (e instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr v
                && iterVar.equals(v.name())) {
            return replacement;
        }
        if (e instanceof InvocationExpr inv) {
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(replaceNextInExpr(arg, iterVar, replacement));
            }
            return new InvocationExpr(
                    inv.target() != null
                            ? replaceNextInExpr(inv.target(), iterVar, replacement) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }
        if (e instanceof BinExpr bin) {
            return new BinExpr(bin.operator(),
                    replaceNextInExpr(bin.left(), iterVar, replacement),
                    replaceNextInExpr(bin.right(), iterVar, replacement));
        }
        if (e instanceof UnExpr un) {
            return new UnExpr(un.operator(),
                    replaceNextInExpr(un.operand(), iterVar, replacement));
        }
        if (e instanceof CastExpr cast) {
            return new CastExpr(cast.targetType(),
                    replaceNextInExpr(cast.operand(), iterVar, replacement));
        }
        return e;
    }

    /**
     * for-each 候选模式数据类,用于在匹配过程中传递上下文信息.
     */
    private static class ForEachCandidate {

        /** 迭代器变量名 */
        final String iterVar;

        /** 可迭代集合/数组表达式 */
        final Expression iterableExpr;

        /** for-each 的循环变量表达式(声明模式中为 null) */
        final Expression elementVar;

        /** 循环体语句(声明模式中为 null) */
        final Statement body;

        /** 用于 Iterator 声明模式的构造器 */
        ForEachCandidate(String iterVar, Expression iterableExpr) {
            this.iterVar = iterVar;
            this.iterableExpr = iterableExpr;
            this.elementVar = null;
            this.body = null;
        }

        /** 用于 while 循环匹配模式的构造器 */
        ForEachCandidate(String iterVar, Expression iterableExpr,
                         Expression elementVar, Statement body) {
            this.iterVar = iterVar;
            this.iterableExpr = iterableExpr;
            this.elementVar = elementVar;
            this.body = body;
        }
    }
}
