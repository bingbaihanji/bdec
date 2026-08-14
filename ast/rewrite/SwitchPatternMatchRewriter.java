package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * 模式匹配 switch 重写器——将 typeSwitch 去糖化产生的合成索引 switch
 * 还原为 instanceof 链.
 *
 * <p>JEP 441 的 typeSwitch({@code SwitchBootstraps.typeSwitch}) 返回
 * 一个 int 索引(-1=null,0..N=各类型),BlockReducer 将其还原为:
 * <pre>
 *   int switchKey = 0;
 *   switch (switchKey) { case -1: ...; case 0: ...; ... }
 * </pre>
 * 但 switchKey 硬编码为 0 无法表达分发逻辑.本重写器将索引 case
 * 按顺序转换为 {@code if (obj instanceof T)} 链:
 * <ul>
 *   <li>case -1 → {@code if (obj == null)}</li>
 *   <li>case k  → 体内含强制转型 {@code T v = (T) obj} 时 → instanceof T</li>
 *   <li>连续同类型的 case(守卫失败贯穿)→ 合并到同一 instanceof 分支</li>
 *   <li>default → 链尾语句</li>
 * </ul>
 */
public class SwitchPatternMatchRewriter implements RewriteRule {

    @Override
    public String name() {return "switch-pattern";}

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

    /** 递归处理语句,在块中检测合成索引 switch 模式 */
    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>(bs.statements());
            for (int i = 0; i < stmts.size() - 1; i++) {
                // 模式: int switchKey = 0; switch (switchKey) {...}
                if (stmts.get(i) instanceof VariableDeclaration vd
                        && "switchKey".equals(vd.name())
                        && stmts.get(i + 1) instanceof SwitchStatement sw
                        && sw.discriminant() instanceof VarExpr dv
                        && "switchKey".equals(dv.name())) {
                    List<Statement> chain = buildInstanceofChain(sw);
                    if (chain != null) {
                        List<Statement> newStmts = new ArrayList<>();
                        for (int k = 0; k < i; k++) {
                            newStmts.add(rewriteStatement(stmts.get(k)));
                        }
                        newStmts.addAll(chain);
                        // 丢弃 switch 之后的死代码(所有路径已在链内返回)
                        return new BlockStatement(newStmts);
                    }
                }
                stmts.set(i, rewriteStatement(stmts.get(i)));
            }
            // 处理最后一条语句(空块跳过)
            if (!stmts.isEmpty()) {
                int last = stmts.size() - 1;
                stmts.set(last, rewriteStatement(stmts.get(last)));
            }
            return new BlockStatement(stmts);
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

    /**
     * 将合成索引 switch 转换为 instanceof 链.
     *
     * @param sw 合成 switch(判别式为 switchKey)
     * @return instanceof 链语句列表,无法转换时返回 null
     */
    private List<Statement> buildInstanceofChain(SwitchStatement sw) {
        List<SwitchStatement.CaseGroup> cases = sw.cases();
        if (cases.isEmpty()) {
            return null;
        }
        // 被测表达式:从第一个含强制转型的 case 体中提取
        Expression testExpr = null;
        for (SwitchStatement.CaseGroup cg : cases) {
            for (Statement st : cg.body()) {
                CastExpr cast = findCastIn(st);
                if (cast != null && cast.operand() != null) {
                    testExpr = cast.operand();
                    break;
                }
            }
            if (testExpr != null) {
                break;
            }
        }
        if (testExpr == null) {
            return null;
        }

        List<Statement> result = new ArrayList<>();
        int idx = 0;
        // 找到 default 组;编号 case 按 key 升序(-1=null 最前)
        SwitchStatement.CaseGroup defGroup = null;
        List<SwitchStatement.CaseGroup> numbered = new ArrayList<>();
        for (SwitchStatement.CaseGroup cg : cases) {
            if (cg.isDefault()) {
                defGroup = cg;
            } else {
                numbered.add(cg);
            }
        }
        numbered.sort((a, b) -> Integer.compare(caseKey(a), caseKey(b)));

        while (idx < numbered.size()) {
            SwitchStatement.CaseGroup cg = numbered.get(idx);
            List<Expression> labels = cg.labels();
            boolean isNullCase = !labels.isEmpty()
                    && labels.getFirst() instanceof LitExpr lit
                    && lit.value() instanceof Integer v && v == -1;

            if (isNullCase) {
                // if (obj == null) { <body> }
                Expression cond = new BinExpr(BinaryOperator.EQ, testExpr,
                        new VarExpr("null"));
                result.add(new IfStatement(cond,
                        blockOf(cg.body()), null));
                idx++;
                continue;
            }

            // 类型 case:体内强制转型 T v = (T) obj
            String typeName = findCastType(cg.body());
            if (typeName == null) {
                return null; // 无法确定类型,放弃转换
            }
            List<Statement> body = new ArrayList<>(cg.body());
            body = stripFirstCast(body);

            // 合并后续同类型的 case(守卫失败贯穿到下一 case)
            while (idx + 1 < numbered.size()) {
                String nextType = findCastType(numbered.get(idx + 1).body());
                if (nextType != null && nextType.equals(typeName)) {
                    body.addAll(stripFirstCast(numbered.get(idx + 1).body()));
                    idx++;
                } else {
                    break;
                }
            }

            Expression cond = new BinExpr(BinaryOperator.INSTANCEOF,
                    testExpr, new VarExpr(typeName));
            result.add(new IfStatement(cond, blockOf(body), null));
            idx++;
        }

        // default 体作为链尾
        if (defGroup != null && !defGroup.body().isEmpty()) {
            result.addAll(defGroup.body());
        }
        return result;
    }

    /** case 标签的数值 key(默认 -1 表示非数值) */
    private int caseKey(SwitchStatement.CaseGroup cg) {
        if (!cg.labels().isEmpty() && cg.labels().getFirst() instanceof LitExpr lit
                && lit.value() instanceof Number n) {
            return n.intValue();
        }
        return Integer.MAX_VALUE;
    }

    /** 在语句中查找强制转型表达式(递归进入 if 条件等) */
    private CastExpr findCastIn(Statement st) {
        if (st instanceof VariableDeclaration vd && vd.initializer() instanceof CastExpr cast) {
            return cast;
        }
        if (st instanceof ExpressionStatement es
                && es.expression() instanceof com.bingbaihanji.bdec.ast.expr.AssignExpr a
                && a.value() instanceof CastExpr cast) {
            return cast;
        }
        if (st instanceof IfStatement i) {
            CastExpr c = findCastInExpr(i.condition());
            if (c != null) {
                return c;
            }
        }
        if (st instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement rs
                && rs.value() != null) {
            return findCastInExpr(rs.value());
        }
        return null;
    }

    private CastExpr findCastInExpr(Expression e) {
        if (e instanceof CastExpr cast) {
            return cast;
        }
        if (e instanceof BinExpr b) {
            CastExpr c = findCastInExpr(b.left());
            return c != null ? c : findCastInExpr(b.right());
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.UnExpr u) {
            return findCastInExpr(u.operand());
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            if (inv.target() != null) {
                CastExpr c = findCastInExpr(inv.target());
                if (c != null) {
                    return c;
                }
            }
            for (Expression a : inv.arguments()) {
                CastExpr c = findCastInExpr(a);
                if (c != null) {
                    return c;
                }
            }
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.FieldAccessExpr fa && fa.target() != null) {
            return findCastInExpr(fa.target());
        }
        return null;
    }

    /** 从 case 体确定强制转型的目标类型简单名 */
    private String findCastType(List<Statement> body) {
        for (Statement st : body) {
            CastExpr cast = findCastIn(st);
            if (cast != null) {
                return simplifyTypeName(cast.targetType());
            }
        }
        return null;
    }

    /** 剥离 case 体首条强制转型声明(instanceof 分支内重新声明) */
    private List<Statement> stripFirstCast(List<Statement> body) {
        List<Statement> result = new ArrayList<>(body);
        if (!result.isEmpty() && findCastIn(result.getFirst()) != null
                && result.getFirst() instanceof VariableDeclaration) {
            result.removeFirst();
        }
        return result;
    }

    private String simplifyTypeName(JavaType t) {
        if (t == null || t.internalName() == null) {
            return "Object";
        }
        String internal = t.internalName();
        int slash = internal.lastIndexOf('/');
        int dollar = internal.lastIndexOf('$');
        int cut = Math.max(slash, dollar);
        return cut >= 0 ? internal.substring(cut + 1) : internal;
    }

    /** 语句列表折叠为单语句或块 */
    private Statement blockOf(List<Statement> stmts) {
        if (stmts.isEmpty()) {
            return new BlockStatement(List.of());
        }
        if (stmts.size() == 1) {
            return stmts.getFirst();
        }
        return new BlockStatement(stmts);
    }
}
