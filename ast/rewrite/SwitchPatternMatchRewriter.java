package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.PatternLabel;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模式匹配 switch 重写器——将 typeSwitch 去糖化产生的合成索引 switch
 * 还原为带模式 case 标签的 switch.
 *
 * <p>JEP 441 的 typeSwitch({@code SwitchBootstraps.typeSwitch}) 返回
 * 一个 int 索引(-1=null,0..N=各类型),BlockReducer 将其还原为:
 * <pre>
 *   Object s = o;
 *   int switchKey = 0;
 *   switch (switchKey) { case -1: ...; case 0: ...; ... }
 * </pre>
 * 本重写器将索引 case 按顺序还原为模式标签:
 * <ul>
 *   <li>case -1 → {@code case null}</li>
 *   <li>case k  → 体内含守卫 {@code if (guard) return X} → {@code case T v when guard'}</li>
 *   <li>case k  → 体内含 {@code T v = (T) obj} 声明 → {@code case T v}</li>
 *   <li>default → {@code default}</li>
 * </ul>
 * 判别式从 {@code switchKey} 替换为被测对象 {@code obj}(由 case 体内的强制转型提取).
 */
public class SwitchPatternMatchRewriter implements RewriteRule {

    private static boolean matchesTypeName(JavaType t, String typeName) {
        return simplifyTypeName(t).equals(typeName);
    }

    private static boolean sameExpr(Expression a, Expression b) {
        if (a instanceof VarExpr va && b instanceof VarExpr vb) {
            return va.name().equals(vb.name());
        }
        return a == b;
    }

    private static String simplifyTypeName(JavaType t) {
        if (t == null || t.internalName() == null) {
            return "Object";
        }
        String internal = t.internalName();
        int slash = internal.lastIndexOf('/');
        int dollar = internal.lastIndexOf('$');
        int cut = Math.max(slash, dollar);
        return cut >= 0 ? internal.substring(cut + 1) : internal;
    }

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
            List<Statement> result = new ArrayList<>();
            int i = 0;
            while (i < stmts.size()) {
                // 模式: int switchKey = 0; switch (switchKey) {...}
                if (i + 1 < stmts.size()
                        && stmts.get(i) instanceof VariableDeclaration vd
                        && "switchKey".equals(vd.name())
                        && stmts.get(i + 1) instanceof SwitchStatement sw
                        && sw.discriminant() instanceof VarExpr dv
                        && "switchKey".equals(dv.name())) {
                    SwitchStatement patternSwitch = buildPatternSwitch(sw);
                    if (patternSwitch != null) {
                        result.add(patternSwitch);
                        // 丢弃 switch 之后的死代码(所有路径已在 case 内返回)
                        return new BlockStatement(result);
                    }
                }
                result.add(rewriteStatement(stmts.get(i)));
                i++;
            }
            return new BlockStatement(result);
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
     * 将合成索引 switch 还原为模式 switch.
     *
     * @param sw 合成 switch(判别式为 switchKey)
     * @return 带模式标签的 switch,无法转换时返回 null
     */
    private SwitchStatement buildPatternSwitch(SwitchStatement sw) {
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

        // 预计算每种类型的模式变量名:同类型多个 case(如守卫 + 无守卫)应
        // 共用同一变量名,优先采用已声明的名称(T v = (T) obj),声明缺失时再生成新名.
        Map<String, String> typeVars = new HashMap<>();
        for (SwitchStatement.CaseGroup cg : numbered) {
            String t = findCastType(cg.body());
            if (t == null) {
                continue;
            }
            String declared = findPatternVarName(cg.body(), t, testExpr);
            if (declared != null) {
                typeVars.put(t, declared); // 已声明名称优先
            } else if (!typeVars.containsKey(t)) {
                typeVars.put(t, freshPatternName(t));
            }
        }

        List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
        for (SwitchStatement.CaseGroup cg : numbered) {
            if (isNullCase(cg)) {
                newCases.add(new SwitchStatement.CaseGroup(
                        List.of(PatternLabel.nullLabel()), cg.body(), false));
                continue;
            }
            String typeName = findCastType(cg.body());
            if (typeName == null) {
                return null; // 无法确定类型,放弃转换
            }
            String varName = typeVars.get(typeName);

            // 守卫 case:体为 [if (guard) return X] → case T v when guard'
            IfStatement guardIf = findGuardIf(cg.body());
            if (guardIf != null) {
                Expression guard = rewriteGuard(guardIf.condition(), typeName, testExpr, varName);
                newCases.add(new SwitchStatement.CaseGroup(
                        List.of(PatternLabel.type(typeName, varName, guard)),
                        List.of(guardIf.thenBranch()), false));
            } else {
                // 无守卫 case:体首条为 T v = (T) obj 声明 → 剥离后为 case T v
                List<Statement> body = stripPatternVarDecl(cg.body());
                newCases.add(new SwitchStatement.CaseGroup(
                        List.of(PatternLabel.type(typeName, varName, null)), body, false));
            }
        }

        // default 体保持原样
        if (defGroup != null) {
            newCases.add(new SwitchStatement.CaseGroup(List.of(), defGroup.body(), true));
        }
        return new SwitchStatement(testExpr, newCases, false);
    }

    /** case 标签的数值 key(默认 -1 表示非数值) */
    private int caseKey(SwitchStatement.CaseGroup cg) {
        if (!cg.labels().isEmpty() && cg.labels().getFirst() instanceof LitExpr lit
                && lit.value() instanceof Number n) {
            return n.intValue();
        }
        return Integer.MAX_VALUE;
    }

    /** case 是否为 null 分支(标签 -1) */
    private boolean isNullCase(SwitchStatement.CaseGroup cg) {
        return !cg.labels().isEmpty()
                && cg.labels().getFirst() instanceof LitExpr lit
                && lit.value() instanceof Integer v && v == -1;
    }

    /** 从 case 体中提取守卫 if(体为单个无 else 的 if 语句) */
    private IfStatement findGuardIf(List<Statement> body) {
        if (body.size() == 1 && body.getFirst() instanceof IfStatement i
                && i.elseBranch() == null) {
            return i;
        }
        return null;
    }

    /** 从 case 体提取模式变量名:首条 {@code T v = (T) obj} 声明 */
    private String findPatternVarName(List<Statement> body, String typeName, Expression testExpr) {
        for (Statement st : body) {
            if (st instanceof VariableDeclaration vd && vd.initializer() instanceof CastExpr cast) {
                if (matchesTypeName(cast.targetType(), typeName)
                        && sameExpr(cast.operand(), testExpr)) {
                    return vd.name();
                }
            }
        }
        return null;
    }

    /** 剥离 case 体首条模式变量声明({@code T v = (T) obj}) */
    private List<Statement> stripPatternVarDecl(List<Statement> body) {
        List<Statement> result = new ArrayList<>(body);
        if (!result.isEmpty() && result.getFirst() instanceof VariableDeclaration vd
                && vd.initializer() instanceof CastExpr) {
            result.removeFirst();
        }
        return result;
    }

    /** 守卫表达式:把 {@code (T) obj} 强转换回模式变量,并去掉装箱的 {@code .intValue()} */
    private Expression rewriteGuard(Expression guard, String typeName, Expression testExpr,
                                    String varName) {
        return new GuardRewriter(typeName, testExpr, varName).transformExpr(guard);
    }

    /** 为模式变量生成全新名称:类型简单名首字母小写(Integer → integer) */
    private String freshPatternName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "v";
        }
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
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
        if (e instanceof InvocationExpr inv) {
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

    /** 守卫重写器:强转 {@code (T) obj} → 模式变量;后续 {@code v.intValue()} → {@code v} */
    private static final class GuardRewriter extends AstTransformer {

        private final String typeName;

        private final Expression testExpr;

        private final String varName;

        GuardRewriter(String typeName, Expression testExpr, String varName) {
            this.typeName = typeName;
            this.testExpr = testExpr;
            this.varName = varName;
        }

        @Override
        protected Expression transformCast(CastExpr e) {
            if (matchesTypeName(e.targetType(), typeName) && sameExpr(e.operand(), testExpr)) {
                return new VarExpr(varName);
            }
            return super.transformCast(e);
        }

        @Override
        protected Expression transformInvocation(InvocationExpr e) {
            Expression r = super.transformInvocation(e);
            if (r instanceof InvocationExpr inv && "intValue".equals(inv.methodName())
                    && inv.target() instanceof VarExpr tv && varName.equals(tv.name())) {
                return new VarExpr(varName);
            }
            return r;
        }
    }
}
