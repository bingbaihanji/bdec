package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * switch 表达式重写器,检测 switch 表达式模式并将其转换为 Java 14+ 的箭头式 switch 表达式(支持 {@code yield}).
 *
 * <p>模式检测:当所有 case 分支(包括 default)均以返回相同形状的表达式结尾,
 * 或所有分支均向同一变量赋值时,将该 switch 标记为表达式形式.</p>
 *
 * <p>参考了 Vineflower 的 {@code SwitchExpressionHelper} 实现.</p>
 */
public class SwitchExprRewriter implements RewriteRule {

    @Override
    public String name() {return "switch-expr";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 重写类型声明中的所有方法,检测并将 switch 语句标记为表达式形式.
     *
     * @param td 待重写的类型声明
     * @return 重写后的类型声明
     */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                boolean nonVoid = md.returnType() != null
                        && md.returnType().kind() != com.bingbaihanji.bdec.type.TypeKind.VOID;
                members.add(withBody(md, md.body() != null ? rewriteStatement(md.body(), nonVoid) : null));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /**
     * 递归遍历语句,对代码块中的 switch 语句进行表达式模式检测.
     *
     * @param s       待处理的语句
     * @param nonVoid 方法是否具有非 void 返回类型
     * @return 处理后的语句
     */
    private Statement rewriteStatement(Statement s, boolean nonVoid) {
        if (s instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            List<Statement> originals = bs.statements();
            for (int idx = 0; idx < originals.size(); idx++) {
                Statement cs = originals.get(idx);
                Statement rs = rewriteStatement(cs, nonVoid);
                if (rs instanceof SwitchStatement sw && isSwitchExpression(sw)) {
                    // 尾部 switch(case 直接 return)在非 void 方法中保持冒号语法:
                    // 转换为箭头表达式后作为裸语句会缺失方法返回值,无法编译.
                    // 保留 case 中的 return 语句,语义与源码一致.
                    if (nonVoid && idx == originals.size() - 1) {
                        rewritten.add(sw);
                    } else {
                        rewritten.add(toSwitchExpression(sw));
                    }
                } else {
                    rewritten.add(rs);
                }
            }
            return new BlockStatement(rewritten);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch(), nonVoid),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch(), nonVoid) : null);
        }
        if (s instanceof LoopStatement l) {
            return withLoopBody(l, rewriteStatement(l.body(), nonVoid));
        }
        return s;
    }

    /**
     * 将 switch 语句转换为 switch 表达式:
     * case 体中的 ReturnStatement 转换为 ExpressionStatement
     *(箭头语法要求表达式而非语句).
     */
    private SwitchStatement toSwitchExpression(SwitchStatement sw) {
        List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            List<Statement> newBody = new ArrayList<>();
            for (Statement s : cg.body()) {
                if (s instanceof ReturnStatement rs && rs.value() != null) {
                    newBody.add(new ExpressionStatement(rs.value()));
                } else if (s instanceof ReturnStatement) {
                    // void return——保留为空表达式
                    newBody.add(new ExpressionStatement(
                            new com.bingbaihanji.bdec.ast.expr.LitExpr(null, JavaType.VOID)));
                } else {
                    newBody.add(s);
                }
            }
            newCases.add(new SwitchStatement.CaseGroup(cg.labels(), newBody, cg.isDefault()));
        }
        return new SwitchStatement(sw.discriminant(), newCases, true);
    }

    /**
     * 判断 switch 语句是否被用作表达式.
     * <p>
     * 检测模式:每个 case 分组(含 default)的最后一个语句要么是 {@code return} 语句,
     * 要么是向同一变量赋值后跟 {@code break} 语句.
     * </p>
     *
     * @param sw 待检测的 switch 语句
     * @return 若可用作表达式则返回 {@code true}
     */
    private boolean isSwitchExpression(SwitchStatement sw) {
        if (sw.cases().isEmpty()) {
            return false;
        }
        String commonTarget = null;

        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            List<Statement> body = cg.body();
            if (body.isEmpty()) {
                return false;
            }
            // 仅支持单语句 case 体(单个 return 或单个赋值+break).
            // 多语句 case 体(如模式匹配 switch 的变量声明+return)
            // 无法安全转换为箭头表达式.
            if (body.size() != 1) {
                return false;
            }
            Statement last = body.getLast();

            if (last instanceof ReturnStatement) {
                // 每个 case 直接 return → 可作为返回值的 switch 表达式
                continue;
            }

            // 检查赋值 + break 模式
            if (last instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr assign
                    && assign.target() instanceof VarExpr ve) {
                if (commonTarget == null) {
                    commonTarget = ve.name();
                } else if (!commonTarget.equals(ve.name())) {
                    // 赋值目标不一致,不是 switch 表达式
                    return false;
                }
            } else {
                // case 结尾既非 return 也非赋值,不是 switch 表达式
                return false;
            }
        }
        return true;
    }
}
