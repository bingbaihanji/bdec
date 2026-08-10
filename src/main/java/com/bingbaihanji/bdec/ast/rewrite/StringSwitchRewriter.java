package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字符串 switch 重写器,检测 javac 生成的字符串 switch 脚手架代码并将其还原为原生 {@code switch (str)} 语句.
 *
 * <p>匹配模式:</p>
 * <pre>
 *   // 第一个 switch:基于 hashCode 的分发
 *   switch (str.hashCode()) {
 *       case hash1:
 *           if (str.equals("foo")) temp = 0;     // 字符串 → 整数的映射
 *           break;
 *       case hash2:
 *           if (str.equals("bar")) temp = 1;
 *           break;
 *       default: break;
 *   }
 *
 *   // 第二个 switch:基于临时变量的实际分支
 *   switch (temp) {
 *       case 0: ...body... break;
 *       case 1: ...body... break;
 *       default: ...body... break;
 *   }
 * </pre>
 * <p>还原为:</p>
 * <pre>
 *   switch (str) {
 *       case "foo": ...body... break;
 *       case "bar": ...body... break;
 *       default: ...body... break;
 *   }
 * </pre>
 *
 * <p>参考了 CFR 的 {@code SwitchReWriter} 和 Vineflower 的 {@code SwitchProcessor} 实现.</p>
 */
public class StringSwitchRewriter implements RewriteRule {

    @Override
    public String name() {return "string-switch";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 重写类型声明中的所有方法体,检测并还原字符串 switch 模式.
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
                        md.body() != null ? rewriteBlock(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /**
     * 递归重写代码块,并对重写后的块进行字符串 switch 模式检测.
     *
     * @param s 待重写的语句
     * @return 重写后的语句
     */
    private Statement rewriteBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement child : bs.statements()) {
                rewritten.add(rewriteBlock(child));
            }
            return detectStringSwitch(new BlockStatement(rewritten));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteBlock(i.thenBranch()),
                    i.elseBranch() != null ? rewriteBlock(i.elseBranch()) : null);
        }
        if (s instanceof LoopStatement l) {
            if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH) {
                return new LoopStatement(l.loopKind(), l.forEachVar(), l.condition(),
                        rewriteBlock(l.body()));
            }
            return new LoopStatement(l.loopKind(), l.condition(), rewriteBlock(l.body()));
        }
        if (s instanceof SwitchStatement sw) {
            // 递归重写嵌套的 switch
            List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
            for (SwitchStatement.CaseGroup cg : sw.cases()) {
                List<Statement> newBody = new ArrayList<>();
                for (Statement cs : cg.body()) {
                    newBody.add(rewriteBlock(cs));
                }
                newCases.add(new SwitchStatement.CaseGroup(cg.labels(), newBody, cg.isDefault()));
            }
            return new SwitchStatement(sw.discriminant(), newCases, sw.isExpression());
        }
        return s;
    }

    /**
     * 遍历代码块查找相邻的 hashCode-switch + temp-switch 模式,并将其合并为原生字符串 switch.
     *
     * @param bs 待检测的代码块
     * @return 合并后的语句
     */
    private Statement detectStringSwitch(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < stmts.size() - 1; i++) {
                // 寻找两个相邻的 SwitchStatement
                if (!(stmts.get(i) instanceof SwitchStatement hashSwitch)) {
                    continue;
                }
                if (!(stmts.get(i + 1) instanceof SwitchStatement tempSwitch)) {
                    continue;
                }

                // 检查第一个 switch 是否为 hashCode() 分发
                HashCodeMatch hashMatch = matchHashCodeSwitch(hashSwitch);
                if (hashMatch == null) {
                    continue;
                }

                // 检查第二个 switch 是否使用相同的临时变量
                TempSwitchMatch tempMatch = matchTempSwitch(tempSwitch, hashMatch);
                if (tempMatch == null) {
                    continue;
                }

                // 构造新的原生字符串 switch
                SwitchStatement stringSwitch = buildStringSwitch(hashMatch, tempMatch);
                if (stringSwitch == null) {
                    continue;
                }

                // 用新 switch 替换原有的两个 switch
                stmts.remove(i + 1);
                stmts.remove(i);
                stmts.add(i, stringSwitch);
                changed = true;
                break;
            }
        } while (changed);

        return new BlockStatement(stmts);
    }

    /**
     * 判断 SwitchStatement 是否匹配 hashCode-switch 模式:{@code switch (xxx.hashCode())}.
     * <p>
     * 提取判别式为 {@code target.hashCode()} 的 switch,
     * 并从各 case 分支中收集字符串到整数的映射关系.
     * </p>
     *
     * @param sw 待匹配的 switch 语句
     * @return 匹配成功则返回 HashCodeMatch 对象,否则返回 {@code null}
     */
    private HashCodeMatch matchHashCodeSwitch(SwitchStatement sw) {
        // 检查判别式是否为 target.hashCode()
        if (!(sw.discriminant() instanceof InvocationExpr inv)) {
            return null;
        }
        if (!"hashCode".equals(inv.methodName())) {
            return null;
        }
        if (inv.target() == null) {
            return null;
        }
        Expression stringVar = inv.target();

        // 从各 case 分支收集映射关系
        LinkedHashMap<Integer, String> hashToString = new LinkedHashMap<>();
        LinkedHashMap<Integer, Integer> hashToTemp = new LinkedHashMap<>();
        String tempVarName = null;
        SwitchStatement.CaseGroup defaultCase = null;

        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            if (cg.isDefault()) {
                defaultCase = cg;
                continue;
            }

            // 每个非 default case 应恰好包含一个整型标签
            int hashCode = extractIntLabel(cg.labels());
            if (hashCode == Integer.MIN_VALUE) {
                continue; // 非整型标签,跳过
            }

            // 从 case 体中提取 equals() 调用中的字符串字面量
            StringMatch sm = extractStringFromCase(cg, stringVar);
            if (sm == null) {
                continue; // 无法从该 case 中提取字符串映射,跳过
            }

            if (tempVarName == null) {
                tempVarName = sm.tempVar;
            } else if (!tempVarName.equals(sm.tempVar)) {
                // 临时变量名不一致,不是合法的字符串 switch
                return null;
            }

            hashToString.put(hashCode, sm.stringValue);
            hashToTemp.put(hashCode, sm.tempValue);
        }

        // 至少需要一个字符串映射才有意义
        if (hashToString.isEmpty() || tempVarName == null) {
            return null;
        }

        return new HashCodeMatch(stringVar, hashToString, hashToTemp, tempVarName, defaultCase);
    }

    /**
     * 从 hashCode switch 的 case 体中提取字符串字面量.
     * <p>匹配模式:{@code if (str.equals("字面量")) tempVar = intVal;}</p>
     *
     * @param cg        case 分组
     * @param stringVar 字符串变量表达式
     * @return 匹配成功则返回 StringMatch 对象,否则返回 {@code null}
     */
    private StringMatch extractStringFromCase(SwitchStatement.CaseGroup cg, Expression stringVar) {
        for (Statement s : cg.body()) {
            if (!(s instanceof IfStatement ifStmt)) {
                continue;
            }
            StringMatch sm = matchEqualsAssign(ifStmt, stringVar);
            if (sm != null) {
                return sm;
            }
        }
        return null;
    }

    /**
     * 匹配 equals 赋值模式:{@code if (str.equals("字面量")) tempVar = intVal;}
     *
     * @param ifStmt        待匹配的 if 语句
     * @param expectedTarget 期望的字符串变量目标
     * @return 匹配成功则返回 StringMatch 对象,否则返回 {@code null}
     */
    private StringMatch matchEqualsAssign(IfStatement ifStmt, Expression expectedTarget) {
        // 条件:str.equals("字面量")
        if (!(ifStmt.condition() instanceof InvocationExpr condInv)) {
            return null;
        }
        if (!"equals".equals(condInv.methodName())) {
            return null;
        }
        if (condInv.arguments().size() != 1) {
            return null;
        }
        if (!(condInv.arguments().get(0) instanceof LitExpr strLit)) {
            return null;
        }
        if (!(strLit.value() instanceof String strValue)) {
            return null;
        }

        // 验证目标变量匹配(字符串变量)
        if (condInv.target() == null) {
            return null;
        }
        // 目标应是与预期变量同名的 VarExpr
        if (expectedTarget instanceof VarExpr ev) {
            if (!(condInv.target() instanceof VarExpr tv)) {
                return null;
            }
            if (!ev.name().equals(tv.name())) {
                return null;
            }
        }
        // 若 expectedTarget 不是 VarExpr,跳过结构比较(当前不支持非 VarExpr 模式)

        // then 分支:将整型字面量赋给临时变量
        AssignmentResult ar = extractAssignment(ifStmt.thenBranch());
        if (ar == null) {
            return null;
        }

        return new StringMatch(strValue, ar.varName, ar.intValue);
    }

    /**
     * 从语句中提取赋值表达式:{@code tempVar = intVal;}
     * 语句可能被包裹在 BlockStatement 中.
     *
     * @param stmt 待提取的语句
     * @return 提取成功则返回 AssignmentResult 对象,否则返回 {@code null}
     */
    private AssignmentResult extractAssignment(Statement stmt) {
        if (stmt instanceof BlockStatement bs) {
            // 在块中寻找赋值语句
            for (Statement s : bs.statements()) {
                AssignmentResult ar = extractAssignment(s);
                if (ar != null) {
                    return ar;
                }
            }
            return null;
        }

        if (!(stmt instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.target() instanceof VarExpr ve)) {
            return null;
        }
        if (!(assign.value() instanceof LitExpr lit)) {
            return null;
        }
        if (!(lit.value() instanceof Integer intVal)) {
            return null;
        }

        return new AssignmentResult(ve.name(), intVal);
    }

    /**
     * 从 case 标签列表中提取单个整型标签.
     *
     * @param labels case 标签表达式列表
     * @return 提取到的整数值,若未找到则返回 {@link Integer#MIN_VALUE}
     */
    private int extractIntLabel(List<Expression> labels) {
        for (Expression label : labels) {
            if (label instanceof LitExpr lit && lit.value() instanceof Integer intVal) {
                return intVal;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 验证 temp switch 是否使用与 hashCode switch 相同的临时变量.
     *
     * @param sw        待匹配的 switch 语句
     * @param hashMatch 已匹配的 hashCode switch 信息
     * @return 匹配成功则返回 TempSwitchMatch 对象,否则返回 {@code null}
     */
    private TempSwitchMatch matchTempSwitch(SwitchStatement sw, HashCodeMatch hashMatch) {
        // 第二个 switch 的判别式应仅为临时变量
        if (!(sw.discriminant() instanceof VarExpr ve)) {
            return null;
        }
        if (!hashMatch.tempVarName.equals(ve.name())) {
            return null;
        }

        // 按整型标签收集 case 分组
        Map<Integer, SwitchStatement.CaseGroup> intToCase = new LinkedHashMap<>();
        SwitchStatement.CaseGroup defaultCase = null;

        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            if (cg.isDefault()) {
                defaultCase = cg;
                continue;
            }
            int labelInt = extractIntLabel(cg.labels());
            if (labelInt != Integer.MIN_VALUE) {
                intToCase.put(labelInt, cg);
            }
        }

        return new TempSwitchMatch(ve.name(), intToCase, defaultCase);
    }

    /**
     * 构建新的原生字符串 switch:{@code switch (str) { case "foo": ... case "bar": ... }}.
     *
     * @param hashMatch hashCode switch 匹配结果
     * @param tempMatch temp switch 匹配结果
     * @return 构建成功则返回新的 SwitchStatement,否则返回 {@code null}
     */
    private SwitchStatement buildStringSwitch(HashCodeMatch hashMatch, TempSwitchMatch tempMatch) {
        // 构建临时整数值到字符串字面量的映射
        Map<Integer, String> tempToString = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : hashMatch.hashToTemp.entrySet()) {
            int hashVal = entry.getKey();
            int tempVal = entry.getValue();
            String strVal = hashMatch.hashToString.get(hashVal);
            if (strVal != null) {
                tempToString.put(tempVal, strVal);
            }
        }

        if (tempToString.isEmpty()) {
            return null;
        }

        // 使用字符串标签构建 case 分组
        List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
        JavaType stringType = JavaType.classType("java/lang/String");

        for (Map.Entry<Integer, String> entry : tempToString.entrySet()) {
            int tempVal = entry.getKey();
            String strVal = entry.getValue();
            SwitchStatement.CaseGroup origCase = tempMatch.intToCase().get(tempVal);
            if (origCase == null) {
                continue; // 该临时值在第二个 switch 中没有匹配的 case
            }

            // 将整型标签替换为字符串字面量标签
            List<Expression> newLabels = List.of(new LitExpr(strVal, stringType));
            newCases.add(new SwitchStatement.CaseGroup(newLabels, origCase.body(), false));
        }

        // 无法重新映射的临时 case 将被跳过(它们不可达)

        // 查找 default case:优先使用 temp switch 的 default
        SwitchStatement.CaseGroup defCase = tempMatch.defaultCase();
        if (defCase != null) {
            newCases.add(new SwitchStatement.CaseGroup(List.of(), defCase.body(), true));
        }

        // 新判别式为原始字符串变量(而非 hashCode 的目标)
        return new SwitchStatement(hashMatch.stringVar, newCases);
    }

    /** hashCode-switch 模式的匹配结果,保存提取到的映射关系. */
    private static class HashCodeMatch {

        /** 字符串变量表达式 */
        final Expression stringVar;

        /** hashCode 到字符串字面量的映射 */
        final LinkedHashMap<Integer, String> hashToString;

        /** hashCode 到临时整数值的映射 */
        final LinkedHashMap<Integer, Integer> hashToTemp;

        /** 临时变量名称 */
        final String tempVarName;

        /** default case 分组(可能为 null) */
        final SwitchStatement.CaseGroup defaultCase;

        HashCodeMatch(Expression stringVar,
                      LinkedHashMap<Integer, String> hashToString,
                      LinkedHashMap<Integer, Integer> hashToTemp,
                      String tempVarName,
                      SwitchStatement.CaseGroup defaultCase) {
            this.stringVar = stringVar;
            this.hashToString = hashToString;
            this.hashToTemp = hashToTemp;
            this.tempVarName = tempVarName;
            this.defaultCase = defaultCase;
        }
    }

    /** temp-switch 模式的匹配结果. */
    private record TempSwitchMatch(
            /** 临时变量名称 */
            String tempVarName,
            /** 整型标签到 case 分组的映射 */
            Map<Integer, SwitchStatement.CaseGroup> intToCase,
            /** default case 分组(可能为 null) */
            SwitchStatement.CaseGroup defaultCase) {}

    /** 从 hashCode switch case 体中提取的字符串匹配信息. */
    private static class StringMatch {

        /** 字符串字面量值 */
        final String stringValue;

        /** 临时变量名 */
        final String tempVar;

        /** 临时变量的整数值 */
        final int tempValue;

        StringMatch(String stringValue, String tempVar, int tempValue) {
            this.stringValue = stringValue;
            this.tempVar = tempVar;
            this.tempValue = tempValue;
        }
    }

    /** 从语句中提取的赋值结果. */
    private static class AssignmentResult {

        /** 被赋值的变量名 */
        final String varName;

        /** 赋值的整数值 */
        final int intValue;

        AssignmentResult(String varName, int intValue) {
            this.varName = varName;
            this.intValue = intValue;
        }
    }
}
