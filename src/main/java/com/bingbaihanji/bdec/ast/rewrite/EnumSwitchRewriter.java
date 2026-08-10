package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 枚举 Switch 重写器,检测 javac 生成的枚举 switch 辅助代码,
 * 将其缩减还原为原生的 {@code switch (enumValue)} 语句.
 *
 * <p>当 javac 编译 {@code switch(enumValue)} 时,会生成一个合成的内部类,
 * 其中包含一个 {@code $SwitchMap$} int 数组,将枚举序数映射为小整数(1, 2, 3...).
 * 实际的 switch 语句会使用该数组:
 * <pre>
 *   switch ($SwitchMap$EnumClass[enumValue.ordinal()]) {
 *       case 1: ... break;
 *       case 2: ... break;
 *   }
 * </pre>
 *
 * <p>本重写器检测 switch 判别式中的 {@code $SwitchMap$} 模式,
 * 将数组加序数表达式替换为原始的枚举变量,并移除编译单元中的合成 SwitchMap 类.
 *
 * <p>设计参考 CFR 的 {@code SwitchReWriter.rewriteEnumSwitch()}
 * 和 Vineflower 的 {@code SwitchMapProcessor}.
 */
public class EnumSwitchRewriter implements RewriteRule {

    /** 判断字段名是否指示 SwitchMap int 数组 */
    private static boolean isSwitchMapFieldName(String name) {
        return name.startsWith("$SwitchMap$") || name.contains("SwitchMap");
    }

    /** 从可能是 {@link FieldAccessExpr} 的表达式中提取字段名 */
    private static String extractFieldName(Expression expr) {
        if (expr instanceof FieldAccessExpr fae) {
            return fae.fieldName();
        }
        return null;
    }

    // ======== 阶段一:收集 SwitchMap 信息 ========

    @Override
    public String name() {return "enum-switch";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        // 阶段一:从所有嵌套层级的类型中收集 $SwitchMap$ 字段名
        Set<String> switchMapFieldNames = new HashSet<>();
        collectSwitchMapFieldNames(unit.types(), switchMapFieldNames);

        // 阶段二:重写每个类型——查找并重写枚举 switch 判别式,移除合成 SwitchMap 类型
        List<TypeDeclaration> newTypes = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            TypeDeclaration rewritten = rewriteType(td, switchMapFieldNames);
            if (rewritten != null) {
                newTypes.add(rewritten);
            }
        }

        return new CompilationUnit(unit.packageName(), unit.imports(), newTypes, unit.innerClassNames());
    }

    /** 递归收集所有类型中类似 SwitchMap 数组的字段名 */
    private void collectSwitchMapFieldNames(List<TypeDeclaration> types, Set<String> names) {
        for (TypeDeclaration td : types) {
            collectFromType(td, names);
        }
    }

    /** 从单个类型声明中收集 SwitchMap 字段名 */
    private void collectFromType(TypeDeclaration td, Set<String> names) {
        for (AstNode member : td.children()) {
            if (member instanceof FieldDeclaration fd) {
                String name = fd.name();
                if (name != null && isSwitchMapFieldName(name)) {
                    names.add(name);
                }
            } else if (member instanceof TypeDeclaration nested) {
                collectFromType(nested, names);
            }
        }
    }

    // ======== 阶段二:重写类型声明 ========

    /**
     * 重写类型声明:
     * <ul>
     *   <li>若该类型本身是 SwitchMap 持有者,返回 {@code null} 将其移除.</li>
     *   <li>否则递归重写方法(转换枚举 switch)和嵌套类型.</li>
     * </ul>
     */
    private TypeDeclaration rewriteType(TypeDeclaration td, Set<String> switchMapFieldNames) {
        if (isSwitchMapType(td, switchMapFieldNames)) {
            return null; // 移除合成 SwitchMap 类型
        }

        List<AstNode> newMembers = new ArrayList<>();
        for (AstNode member : td.children()) {
            if (member instanceof TypeDeclaration nested) {
                TypeDeclaration rewritten = rewriteType(nested, switchMapFieldNames);
                if (rewritten != null) {
                    newMembers.add(rewritten);
                }
            } else if (member instanceof MethodDeclaration md) {
                newMembers.add(rewriteMethod(md, switchMapFieldNames));
            } else {
                newMembers.add(member);
            }
        }

        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), newMembers);
    }

    /**
     * 判断类型是否为应移除的合成 SwitchMap 持有者.
     * 检查类型名是否匹配 SwitchMap 模式,以及是否包含 {@code $SwitchMap$} 静态字段.
     */
    private boolean isSwitchMapType(TypeDeclaration td, Set<String> switchMapFieldNames) {
        String name = td.simpleName();
        if (name != null && (name.startsWith("$SwitchMap$") || name.contains("SwitchMap"))) {
            return true;
        }
        // 匿名内部类持有者:检查该类型的字段是否包含已识别的 $SwitchMap$ 字段
        for (AstNode member : td.children()) {
            if (member instanceof FieldDeclaration fd) {
                String fieldName = fd.name();
                if (fieldName != null && switchMapFieldNames.contains(fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 重写方法声明中的方法体 */
    private MethodDeclaration rewriteMethod(MethodDeclaration md, Set<String> switchMapFieldNames) {
        Statement newBody = md.body() != null ? rewriteStatement(md.body(), switchMapFieldNames) : null;
        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                md.parameterNames(), md.parameterTypes(), md.typeParameters(), newBody);
    }

    /** 递归重写各种语句类型 */
    private Statement rewriteStatement(Statement s, Set<String> switchMapFieldNames) {
        if (s instanceof BlockStatement bs) {
            List<Statement> newStmts = new ArrayList<>();
            for (Statement st : bs.statements()) {
                newStmts.add(rewriteStatement(st, switchMapFieldNames));
            }
            return new BlockStatement(newStmts);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch(), switchMapFieldNames),
                    i.elseBranch() != null
                            ? rewriteStatement(i.elseBranch(), switchMapFieldNames) : null);
        }
        if (s instanceof LoopStatement l) {
            if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH) {
                return new LoopStatement(l.loopKind(), l.forEachVar(), l.condition(),
                        rewriteStatement(l.body(), switchMapFieldNames));
            }
            return new LoopStatement(l.loopKind(), l.condition(),
                    rewriteStatement(l.body(), switchMapFieldNames));
        }
        if (s instanceof TryStatement t) {
            List<TryStatement.CatchClause> newCatches = new ArrayList<>();
            for (TryStatement.CatchClause cc : t.catchClauses()) {
                newCatches.add(new TryStatement.CatchClause(
                        cc.exceptionType(), cc.varName(),
                        rewriteStatement(cc.body(), switchMapFieldNames)));
            }
            return new TryStatement(
                    rewriteStatement(t.tryBody(), switchMapFieldNames),
                    newCatches,
                    t.finallyBody() != null
                            ? rewriteStatement(t.finallyBody(), switchMapFieldNames) : null);
        }
        if (s instanceof SynchronizedStatement sync) {
            return new SynchronizedStatement(sync.monitorObject(),
                    rewriteStatement(sync.body(), switchMapFieldNames));
        }
        if (s instanceof SwitchStatement sw) {
            return rewriteSwitch(sw, switchMapFieldNames);
        }
        return s;
    }

    /**
     * 检查 switch 判别式是否使用了 SwitchMap 模式,若是则重写;
     * 同时递归处理各 case 分支体.
     */
    private SwitchStatement rewriteSwitch(SwitchStatement sw, Set<String> switchMapFieldNames) {
        // 尝试检测并重写枚举 switch 判别式
        Expression newDiscriminant = tryRewriteDiscriminant(sw.discriminant(), switchMapFieldNames);

        // 递归处理 case 分支体
        List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            List<Statement> newBody = new ArrayList<>();
            for (Statement cs : cg.body()) {
                newBody.add(rewriteStatement(cs, switchMapFieldNames));
            }
            newCases.add(new SwitchStatement.CaseGroup(cg.labels(), newBody, cg.isDefault()));
        }

        return new SwitchStatement(newDiscriminant, newCases, sw.isExpression());
    }

    /**
     * 尝试匹配枚举 switch 模式并提取枚举变量.
     * 模式:{@code $SwitchMap$XXX[enumValue.ordinal()]}.
     *
     * <p>在 AST 中表示为:
     * <pre>
     *   ArrayAccessExpr(
     *       FieldAccessExpr(target, "$SwitchMap$..."),  // int 数组
     *       InvocationExpr(enumVar, "ordinal", [])       // enumVar.ordinal()
     *   )
     * </pre>
     *
     * @param discriminant 原始 switch 判别式
     * @param switchMapFieldNames 已收集的 SwitchMap 字段名集合
     * @return 若匹配成功则返回枚举变量表达式,否则返回原始表达式
     */
    private Expression tryRewriteDiscriminant(Expression discriminant,
                                              Set<String> switchMapFieldNames) {
        if (!(discriminant instanceof ArrayAccessExpr arrayAccess)) {
            return discriminant;
        }

        Expression arrayExpr = arrayAccess.array();
        Expression indexExpr = arrayAccess.index();

        // 被访问的数组必须引用 $SwitchMap$ 字段
        String fieldName = extractFieldName(arrayExpr);
        if (fieldName == null || !isSwitchMapFieldName(fieldName)) {
            return discriminant;
        }

        // 数组索引必须是 ordinal() 调用且无参数
        if (!(indexExpr instanceof InvocationExpr inv)) {
            return discriminant;
        }
        if (!"ordinal".equals(inv.methodName())) {
            return discriminant;
        }
        if (!inv.arguments().isEmpty()) {
            return discriminant;
        }

        // 从 ordinal() 的调用目标中提取枚举变量
        Expression enumTarget = inv.target();
        if (enumTarget == null) {
            return discriminant;
        }

        return enumTarget;
    }
}
