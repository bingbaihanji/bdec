package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * 三元表达式重写器,将向同一变量赋值或从两个分支 return 的 if-else 语句折叠为三元表达式({@code ? :}).
 *
 * <p>支持的模式:</p>
 * <pre>
 *   if(cond) x = a; else x = b;  →  x = cond ? a : b;
 *   if(cond) return a; else return b;  →  return cond ? a : b;
 * </pre>
 * <p>同时会自动规范化 {@code !x ? a : b} 为 {@code x ? b : a}.</p>
 *
 * <p>参考了 CFR 的 {@code ConditionalRewriter} 实现.</p>
 */
public class TernaryRewriter implements RewriteRule {

    @Override
    public String name() {return "ternary";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> rewrittenTypes = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            rewrittenTypes.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), rewrittenTypes, unit.innerClassNames());
    }

    /**
     * 重写类型声明,对其所有方法体进行三元表达式折叠.
     *
     * @param td 待重写的类型声明
     * @return 重写后的类型声明
     */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> rewrittenMembers = new ArrayList<>();
        for (AstNode member : td.children()) {
            if (member instanceof MethodDeclaration md) {
                Statement newBody = md.body() != null ? rewriteStatement(md.body()) : null;
                rewrittenMembers.add(new MethodDeclaration(
                        md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(), newBody));
            } else {
                rewrittenMembers.add(member);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), rewrittenMembers);
    }

    /**
     * 递归重写语句,尝试将 if-else 折叠为三元表达式.
     *
     * @param stmt 待重写的语句
     * @return 重写后的语句
     */
    private Statement rewriteStatement(Statement stmt) {
        if (stmt instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement s : bs.statements()) {
                rewritten.add(rewriteStatement(s));
            }
            return collapseTernaries(new BlockStatement(rewritten));
        }
        if (stmt instanceof IfStatement ifStmt) {
            Statement thenBody = rewriteStatement(ifStmt.thenBranch());
            Statement elseBody = ifStmt.elseBranch() != null
                    ? rewriteStatement(ifStmt.elseBranch()) : null;
            // 尝试直接折叠为赋值三元表达式
            Expression ternary = tryCollapse(ifStmt.condition(), thenBody, elseBody);
            if (ternary != null) {
                return new ExpressionStatement(ternary);
            }
            // 尝试折叠为 return 三元表达式:if(cond) return a; else return b;
            ternary = tryReturnTernary(ifStmt.condition(), thenBody, elseBody);
            if (ternary != null) {
                return new ReturnStatement(ternary);
            }
            return new IfStatement(ifStmt.condition(), thenBody, elseBody);
        }
        return stmt;
    }

    /**
     * 遍历代码块,将相邻的符合三元模式条件的 if-else 折叠为三元表达式.
     * <p>循环迭代处理直到无法继续折叠为止,因为一次折叠可能产生新的可折叠模式.</p>
     *
     * @param bs 待处理的代码块
     * @return 折叠后的代码块
     */
    private Statement collapseTernaries(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < stmts.size(); i++) {
                Statement s = stmts.get(i);
                if (s instanceof IfStatement ifStmt && ifStmt.elseBranch() != null) {
                    // 尝试赋值三元折叠
                    Expression ternary = tryCollapse(ifStmt.condition(),
                            ifStmt.thenBranch(), ifStmt.elseBranch());
                    if (ternary != null) {
                        stmts.set(i, new ExpressionStatement(ternary));
                        changed = true;
                        break;
                    }
                    // 尝试 return 三元折叠
                    ternary = tryReturnTernary(ifStmt.condition(),
                            ifStmt.thenBranch(), ifStmt.elseBranch());
                    if (ternary != null) {
                        stmts.set(i, new ReturnStatement(ternary));
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return new BlockStatement(stmts);
    }

    /**
     * 尝试将 if-else 折叠为赋值三元表达式:{@code x = cond ? a : b}.
     * <p>当 then 和 else 分支均为向同一变量的赋值语句时触发折叠.</p>
     *
     * @param cond      if 条件表达式
     * @param thenBody  then 分支语句
     * @param elseBody  else 分支语句
     * @return 折叠成功则返回 AssignExpr,否则返回 {@code null}
     */
    private Expression tryCollapse(Expression cond, Statement thenBody, Statement elseBody) {
        if (elseBody == null) {
            return null;
        }
        Expression thenExpr = singleExpr(thenBody);
        Expression elseExpr = singleExpr(elseBody);
        if (thenExpr == null || elseExpr == null) {
            return null;
        }

        // 当两个分支都是对同一变量的赋值时触发折叠
        if (thenExpr instanceof AssignExpr ta && elseExpr instanceof AssignExpr ea) {
            if (ta.target() instanceof VarExpr tv && ea.target() instanceof VarExpr ev) {
                if (tv.name().equals(ev.name())) {
                    return new AssignExpr(ta.target(),
                            new CondExpr(cond, ta.value(), ea.value()),
                            ta.compoundOp()); // 保留复合赋值运算符
                }
            }
        }
        return null;
    }

    /**
     * 尝试将 if-else 折叠为 return 三元表达式:{@code return cond ? a : b}.
     * <p>
     * 当 then 和 else 分支均以 return 语句结尾时触发折叠.
     * 同时自动规范化 {@code !x ? a : b} 为 {@code x ? b : a}.
     * </p>
     *
     * @param cond      if 条件表达式
     * @param thenBody  then 分支语句
     * @param elseBody  else 分支语句
     * @return 折叠成功则返回 CondExpr,否则返回 {@code null}
     */
    private Expression tryReturnTernary(Expression cond, Statement thenBody, Statement elseBody) {
        if (elseBody == null) {
            return null;
        }

        Statement thenStmt = thenBody;
        Statement elseStmt = elseBody;
        // 若 then 分支为单语句块,则解包
        if (thenBody instanceof BlockStatement tb && tb.statements().size() == 1) {
            thenStmt = tb.statements().getFirst();
        }
        // 若 else 分支为单语句块,则解包
        if (elseBody instanceof BlockStatement eb && eb.statements().size() == 1) {
            elseStmt = eb.statements().getFirst();
        }

        if (thenStmt instanceof ReturnStatement tr
                && elseStmt instanceof ReturnStatement er) {
            Expression thenVal = tr.value();
            Expression elseVal = er.value();
            if (thenVal == null) {
                thenVal = new VarExpr("/*void*/");
            }
            if (elseVal == null) {
                elseVal = new VarExpr("/*void*/");
            }

            // 规范化:!x ? a : b → x ? b : a
            if (cond instanceof com.bingbaihanji.bdec.ast.expr.UnExpr ue
                    && ue.operator() == com.bingbaihanji.bdec.ast.expr.UnaryOperator.NOT) {
                return new CondExpr(ue.operand(), elseVal, thenVal);
            }
            return new CondExpr(cond, thenVal, elseVal);
        }
        return null;
    }

    /**
     * 从语句中提取单个表达式,自动解包单语句代码块.
     *
     * @param s 待提取的语句
     * @return 提取到的表达式,若非表达式语句则返回 {@code null}
     */
    private Expression singleExpr(Statement s) {
        if (s instanceof ExpressionStatement es) {
            return es.expression();
        }
        if (s instanceof BlockStatement bs && bs.statements().size() == 1) {
            return singleExpr(bs.statements().getFirst());
        }
        return null;
    }
}
