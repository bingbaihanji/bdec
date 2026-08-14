package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * 字面量数组内联重写器——将 javac 的数组初始化展开还原为内联初始化器.
 *
 * <p>检测模式:
 * <pre>
 *   String[] tmp0 = new String[3];
 *   tmp0[0] = "a";
 *   tmp0[1] = "b";
 *   tmp0[2] = "c";
 *   Arrays.asList(tmp0)   // tmp0 仅此一处引用
 * </pre>
 * 还原为 {@code Arrays.asList(new String[]{"a", "b", "c"})}.
 * 仅当数组变量此后被引用恰好一次时内联(多处引用时内联会
 * 产生多个独立数组实例,改变共享语义).
 */
public class ArrayInlineRewriter implements RewriteRule {

    @Override
    public String name() {return "array-inline";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types,
                unit.innerClassNames());
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                Statement newBody = rewriteStatement(md.body());
                members.add(withBody(md, newBody instanceof BlockStatement bs ? bs : newBody));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>(bs.statements());
            for (int i = 0; i < stmts.size(); i++) {
                // 模式: T[] v = new T[n];
                if (stmts.get(i) instanceof VariableDeclaration vd
                        && vd.initializer() instanceof NewExpr ne
                        && ne.dimensions().size() == 1
                        && ne.dimensions().getFirst() instanceof LitExpr dimLit
                        && dimLit.value() instanceof Number n) {
                    int count = n.intValue();
                    String varName = vd.name();
                    // 收集 v[0..count-1] = 常量 赋值
                    List<Expression> elems = new ArrayList<>();
                    int j = i + 1;
                    while (j < stmts.size() && elems.size() < count) {
                        Statement st = stmts.get(j);
                        if (st instanceof ExpressionStatement es
                                && es.expression() instanceof AssignExpr a
                                && a.target() instanceof ArrayAccessExpr aa
                                && aa.array() instanceof VarExpr av
                                && varName.equals(av.name())
                                && aa.index() instanceof LitExpr il
                                && il.value() instanceof Integer idx
                                && idx == elems.size()) {
                            elems.add(a.value());
                            j++;
                        } else {
                            break;
                        }
                    }
                    if (elems.size() != count) {
                        continue;
                    }
                    // 引用计数:后续语句中 varName 出现的次数
                    int refCount = countRefs(stmts, j, varName);
                    if (refCount != 1) {
                        continue; // 多处引用——内联会改变共享语义
                    }
                    // 构建内联数组表达式
                    JavaType elemType = vd.type().arrayDimensions() > 0
                            ? stripArrayDim(vd.type()) : vd.type();
                    NewExpr inline = new NewExpr(vd.type(), List.of(), List.of(),
                            List.of(), elems);
                    // 删除声明与赋值;替换引用处
                    List<Statement> newStmts = new ArrayList<>();
                    for (int k = 0; k < i; k++) {
                        newStmts.add(stmts.get(k));
                    }
                    for (int k = j; k < stmts.size(); k++) {
                        Statement sk = stmts.get(k);
                        boolean isReset = (sk instanceof VariableDeclaration vd2
                                && varName.equals(vd2.name()))
                                || (sk instanceof ExpressionStatement es2
                                && es2.expression() instanceof AssignExpr a2
                                && a2.target() instanceof VarExpr tv2
                                && varName.equals(tv2.name()));
                        if (isReset) {
                            // 同名变量被重新初始化:重赋值(tmp0 = new T[n])
                            // 转为声明(内联已删除原声明,否则 tmp0 未声明)
                            if (sk instanceof ExpressionStatement es2
                                    && es2.expression() instanceof AssignExpr a2
                                    && a2.target() instanceof VarExpr tv2
                                    && varName.equals(tv2.name())
                                    && a2.value() instanceof NewExpr ne2) {
                                newStmts.add(new VariableDeclaration(
                                        ne2.instantiatedType(), varName, ne2));
                                k++;
                            }
                            for (int k2 = k; k2 < stmts.size(); k2++) {
                                newStmts.add(stmts.get(k2));
                            }
                            break;
                        }
                        newStmts.add(replaceVar(sk, varName, inline));
                    }
                    return new BlockStatement(newStmts);
                }
            }
            // 递归处理子语句
            List<Statement> rewritten = new ArrayList<>();
            for (Statement c : stmts) {
                rewritten.add(rewriteStatement(c));
            }
            return new BlockStatement(rewritten);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        if (s instanceof LoopStatement l) {
            return withLoopBody(l, rewriteStatement(l.body()));
        }
        return s;
    }

    /** 统计语句列表中变量名的引用次数.
     *  止于同名变量的下一次声明(槽位复用产生的新数组变量) */
    private int countRefs(List<Statement> stmts, int from, String varName) {
        int n = 0;
        for (int k = from; k < stmts.size(); k++) {
            Statement s = stmts.get(k);
            if (s instanceof VariableDeclaration vd && varName.equals(vd.name())) {
                break; // 同名重声明——后续引用属于新变量
            }
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr a
                    && a.target() instanceof VarExpr tv
                    && varName.equals(tv.name())) {
                break; // 同名重赋值(tmp0 = new T[n])——后续引用属于新变量
            }
            n += countRefsInStmt(s, varName);
        }
        return n;
    }

    private int countRefsInStmt(Statement s, String varName) {
        if (s instanceof ExpressionStatement es) {
            return countRefsInExpr(es.expression(), varName);
        }
        if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            return countRefsInExpr(vd.initializer(), varName);
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement rs && rs.value() != null) {
            return countRefsInExpr(rs.value(), varName);
        }
        if (s instanceof IfStatement i) {
            return countRefsInExpr(i.condition(), varName)
                    + countRefsInStmt(i.thenBranch(), varName)
                    + (i.elseBranch() != null ? countRefsInStmt(i.elseBranch(), varName) : 0);
        }
        return 0;
    }

    private int countRefsInExpr(Expression e, String varName) {
        if (e == null) {
            return 0;
        }
        if (e instanceof VarExpr v) {
            return varName.equals(v.name()) ? 1 : 0;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.BinExpr b) {
            return countRefsInExpr(b.left(), varName) + countRefsInExpr(b.right(), varName);
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            int n = inv.target() != null ? countRefsInExpr(inv.target(), varName) : 0;
            for (Expression a : inv.arguments()) {
                n += countRefsInExpr(a, varName);
            }
            return n;
        }
        return 0;
    }

    /** 在语句中替换变量引用为内联数组表达式 */
    private Statement replaceVar(Statement s, String varName, NewExpr inline) {
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(replaceVarInExpr(es.expression(), varName, inline));
        }
        if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            return new VariableDeclaration(vd.type(), vd.name(),
                    replaceVarInExpr(vd.initializer(), varName, inline),
                    vd.typeAnnotations());
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement rs && rs.value() != null) {
            return new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(
                    replaceVarInExpr(rs.value(), varName, inline));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(replaceVarInExpr(i.condition(), varName, inline),
                    replaceVar(i.thenBranch(), varName, inline),
                    i.elseBranch() != null ? replaceVar(i.elseBranch(), varName, inline) : null);
        }
        if (s instanceof BlockStatement bs) {
            List<Statement> inner = new ArrayList<>();
            for (Statement c : bs.statements()) {
                inner.add(replaceVar(c, varName, inline));
            }
            return new BlockStatement(inner);
        }
        return s;
    }

    private Expression replaceVarInExpr(Expression e, String varName, NewExpr inline) {
        if (e == null) {
            return null;
        }
        if (e instanceof VarExpr v) {
            return varName.equals(v.name()) ? inline : e;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.BinExpr b) {
            return new com.bingbaihanji.bdec.ast.expr.BinExpr(b.operator(),
                    replaceVarInExpr(b.left(), varName, inline),
                    replaceVarInExpr(b.right(), varName, inline));
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            List<Expression> args = new ArrayList<>();
            for (Expression a : inv.arguments()) {
                args.add(replaceVarInExpr(a, varName, inline));
            }
            return new com.bingbaihanji.bdec.ast.expr.InvocationExpr(
                    inv.target() != null ? replaceVarInExpr(inv.target(), varName, inline) : null,
                    inv.methodName(), args, inv.returnType());
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.CastExpr cast) {
            return new com.bingbaihanji.bdec.ast.expr.CastExpr(cast.targetType(),
                    replaceVarInExpr(cast.operand(), varName, inline));
        }
        return e;
    }

    /** 剥离数组维度得到元素类型 */
    private JavaType stripArrayDim(JavaType t) {
        if (t.arrayDimensions() <= 1) {
            return t;
        }
        return new JavaType(t.kind(), t.internalName(), t.descriptor(),
                t.typeArguments(), t.arrayDimensions() - 1);
    }
}
