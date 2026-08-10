package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;

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
        List<Statement> stmts = new ArrayList<>(bs.statements());
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

    /** 匹配:{@code Iterator iter = collection.iterator();} */
    private ForEachCandidate matchIteratorDecl(Statement s) {
        if (!(s instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.value() instanceof InvocationExpr inv)) {
            return null;
        }
        if (!"iterator".equals(inv.methodName())) {
            return null;
        }
        if (inv.target() == null) {
            return null;
        }

        // 提取迭代器变量名
        String varName = null;
        if (assign.target() instanceof VarExpr vx) {
            varName = vx.name();
        }
        if (varName == null) {
            return null;
        }

        return new ForEachCandidate(varName, inv.target());
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
        if (!(first instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.value() instanceof InvocationExpr nextInv)) {
            return null;
        }
        if (!"next".equals(nextInv.methodName())) {
            return null;
        }
        if (!(nextInv.target() instanceof VarExpr nextVar)) {
            return null;
        }
        if (!candidate.iterVar.equals(nextVar.name())) {
            return null;
        }

        // 构建新循环体(移除 next() 调用)
        List<Statement> newBodyStmts = new ArrayList<>(bodyStmts);
        newBodyStmts.remove(0);
        Statement newBody;
        if (newBodyStmts.isEmpty()) {
            newBody = new BlockStatement(List.of());
        } else if (newBodyStmts.size() == 1) {
            newBody = newBodyStmts.get(0);
        } else {
            newBody = new BlockStatement(newBodyStmts);
        }

        return new ForEachCandidate(candidate.iterVar, candidate.iterableExpr,
                assign.target(), newBody);
    }

    /** 提取语句中的子语句列表(若为块语句则展开,否则包装为单元素列表) */
    private List<Statement> getBodyStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new ArrayList<>(bs.statements());
        }
        return new ArrayList<>(List.of(s));
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
