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
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        md.typeParameters(),
                        md.body() != null ? rewriteStatement(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /**
     * 递归遍历语句,对代码块中的 switch 语句进行表达式模式检测.
     *
     * @param s 待处理的语句
     * @return 处理后的语句
     */
    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement cs : bs.statements()) {
                Statement rs = rewriteStatement(cs);
                if (rs instanceof SwitchStatement sw && isSwitchExpression(sw)) {
                    // 将其标记为 switch 表达式
                    rewritten.add(new SwitchStatement(sw.discriminant(), sw.cases(), true));
                } else {
                    rewritten.add(rs);
                }
            }
            return new BlockStatement(rewritten);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        if (s instanceof LoopStatement l) {
            if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH) {
                return new LoopStatement(l.loopKind(), l.forEachVar(), l.condition(),
                        rewriteStatement(l.body()));
            }
            return new LoopStatement(l.loopKind(), l.condition(), rewriteStatement(l.body()));
        }
        return s;
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
