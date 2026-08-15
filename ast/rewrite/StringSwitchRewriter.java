package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
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
    public RewriteRuleKind kind() {return RewriteRuleKind.STRING_SWITCH;}

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
                members.add(withBody(md, md.body() != null ? rewriteBlock(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
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
            return withLoopBody(l, rewriteBlock(l.body()));
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
                // 寻找两个相邻的 SwitchStatement.
                // hash switch 可能被前置声明包装(如 "String var2 = s;
                // int var3 = -1;" 与 hash switch 同在一个块中),也可能
                // 被其他重写器扁平化,声明位于 switch 之前的相邻语句.
                // 两种形态都识别:包装块(最后一个语句为 switch)或
                // 直接相邻(前置的 temp/stringVar 声明语句).
                SwitchStatement hashSwitch = null;
                List<Statement> preStmts = new ArrayList<>();
                if (stmts.get(i) instanceof SwitchStatement sw) {
                    hashSwitch = sw;
                } else if (stmts.get(i) instanceof BlockStatement wrap) {
                    List<Statement> inner = wrap.statements();
                    if (!inner.isEmpty()
                            && inner.get(inner.size() - 1) instanceof SwitchStatement sw2) {
                        hashSwitch = sw2;
                        preStmts.addAll(inner.subList(0, inner.size() - 1));
                    }
                }
                if (hashSwitch == null) {
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

                // 前置声明中,临时变量与字符串副本变量(name 与 stringVar
                // 同名的声明)在还原后不再使用——丢弃,保持输出干净.
                // 字符串副本(String var2 = s;)的初始值即原始判别式:
                // 副本变量被丢弃后,switch 判别式必须替换为其初始值.
                String stringVarName = hashMatch.stringVar instanceof VarExpr sv
                        ? sv.name() : null;
                // 直接相邻形态:收集 hash switch 之前的相邻 temp/stringVar 声明
                int removedBefore = 0;
                if (preStmts.isEmpty()) {
                    int j = i - 1;
                    while (j >= 0) {
                        Statement c = stmts.get(j);
                        if (c instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                                && (vd.name().equals(hashMatch.tempVarName)
                                || (stringVarName != null
                                && vd.name().equals(stringVarName)))) {
                            preStmts.add(0, c);
                            removedBefore++;
                            j--;
                        } else {
                            break;
                        }
                    }
                }
                Expression discriminantOverride = null;
                List<Statement> keptPre = new ArrayList<>();
                for (Statement ps : preStmts) {
                    if (ps instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                            && vd.name().equals(hashMatch.tempVarName)) {
                        continue;
                    }
                    if (ps instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                            && stringVarName != null && vd.name().equals(stringVarName)
                            && vd.initializer() != null) {
                        discriminantOverride = vd.initializer();
                        continue;
                    }
                    keptPre.add(ps);
                }

                // 构造新的原生字符串 switch
                SwitchStatement stringSwitch = buildStringSwitch(
                        hashMatch, tempMatch, discriminantOverride);
                if (stringSwitch == null) {
                    continue;
                }

                // 用新 switch 替换原有的两个 switch(及已收集的前置声明).
                // 前置声明连续位于索引 i-removedBefore..i-1,
                // 移除第一个后其余前移——重复移除同一索引.
                stmts.remove(i + 1);
                stmts.remove(i);
                for (int k = 0; k < removedBefore; k++) {
                    stmts.remove(i - removedBefore);
                }
                int insertAt = i - removedBefore;
                if (keptPre.isEmpty()) {
                    stmts.add(insertAt, stringSwitch);
                } else {
                    List<Statement> merged = new ArrayList<>(keptPre);
                    merged.add(stringSwitch);
                    stmts.add(insertAt, new BlockStatement(merged));
                }
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

        // 从各 case 分支收集映射关系.同一 hashCode 可能对应多个字符串
        //(hash 碰撞,如 "Aa"/"BB" 同为 2112),javac 在一个 hash case 内用
        // if-else-if 链分发到不同 temp 值——须收集全部,而非每 case 取一个.
        LinkedHashMap<Integer, String> tempToString = new LinkedHashMap<>();
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

            // 从 case 体中递归提取 equals() 调用中的全部字符串映射
            List<StringMatch> matches = extractStringMatches(cg, stringVar);
            for (StringMatch sm : matches) {
                if (tempVarName == null) {
                    tempVarName = sm.tempVar;
                } else if (!tempVarName.equals(sm.tempVar)) {
                    // 临时变量名不一致,不是合法的字符串 switch
                    return null;
                }
                // 同一 temp 值映射到不同字符串 → 非法;重复字符串幂等忽略
                String prev = tempToString.putIfAbsent(sm.tempValue, sm.stringValue);
                if (prev != null && !prev.equals(sm.stringValue)) {
                    return null;
                }
            }
        }

        // 至少需要一个字符串映射才有意义
        if (tempToString.isEmpty() || tempVarName == null) {
            return null;
        }

        return new HashCodeMatch(stringVar, tempToString, tempVarName, defaultCase);
    }

    /**
     * 从 hashCode switch 的 case 体中递归提取全部字符串字面量映射.
     * <p>匹配两种形态:</p>
     * <ul>
     *   <li>直接:{@code if (str.equals("字面量")) tempVar = intVal;}</li>
     *   <li>取反(短分支优先规范化):{@code if (!str.equals("字面量")) {...} else { tempVar = intVal; }}</li>
     * </ul>
     * <p>hash 碰撞时 javac 在同一个 hash case 内生成 if-else-if 链
     * ({@code if (str.equals("A")) t=1; else if (str.equals("B")) t=2;}),
     * 递归遍历以收集全部映射,而非每 case 仅取首个 IfStatement.</p>
     *
     * @param cg        case 分组
     * @param stringVar 字符串变量表达式
     * @return 提取到的全部 StringMatch(可能为空列表)
     */
    private List<StringMatch> extractStringMatches(SwitchStatement.CaseGroup cg, Expression stringVar) {
        List<StringMatch> out = new ArrayList<>();
        for (Statement s : cg.body()) {
            collectStringMatches(s, stringVar, out);
        }
        return out;
    }

    /** 递归遍历语句,收集所有 {@code str.equals(字面量)→temp} 映射. */
    private void collectStringMatches(Statement stmt, Expression stringVar, List<StringMatch> out) {
        if (stmt instanceof BlockStatement bs) {
            for (Statement s : bs.statements()) {
                collectStringMatches(s, stringVar, out);
            }
            return;
        }
        if (!(stmt instanceof IfStatement ifStmt)) {
            return;
        }
        // 直接 equals 形态:then 分支是赋值
        StringMatch direct = matchEqualsAssign(ifStmt, stringVar);
        if (direct != null) {
            out.add(direct);
            collectStringMatches(ifStmt.elseBranch(), stringVar, out);
            return;
        }
        // 取反 equals 形态(短分支优先):else 分支是赋值
        StringMatch negated = matchNegatedEqualsAssign(ifStmt, stringVar);
        if (negated != null) {
            out.add(negated);
            collectStringMatches(ifStmt.thenBranch(), stringVar, out);
            return;
        }
        // 其他条件:两侧递归(防御性,避免遗漏嵌套 else-if)
        collectStringMatches(ifStmt.thenBranch(), stringVar, out);
        collectStringMatches(ifStmt.elseBranch(), stringVar, out);
    }

    /** 匹配取反形态:{@code if (!str.equals("字面量")) {...} else { tempVar = intVal; }}. */
    private StringMatch matchNegatedEqualsAssign(IfStatement ifStmt, Expression expectedTarget) {
        if (!(ifStmt.condition() instanceof UnExpr ue)) {
            return null;
        }
        if (ue.operator() != UnaryOperator.NOT) {
            return null;
        }
        if (!(ue.operand() instanceof InvocationExpr condInv)) {
            return null;
        }
        if (!"equals".equals(condInv.methodName()) || condInv.arguments().size() != 1) {
            return null;
        }
        if (!(condInv.arguments().get(0) instanceof LitExpr strLit)) {
            return null;
        }
        if (!(strLit.value() instanceof String strValue)) {
            return null;
        }
        // 验证目标变量匹配(字符串变量)
        if (expectedTarget instanceof VarExpr ev) {
            if (!(condInv.target() instanceof VarExpr tv)) {
                return null;
            }
            if (!ev.name().equals(tv.name())) {
                return null;
            }
        }
        // else 分支:将整型字面量赋给临时变量
        AssignmentResult ar = extractAssignment(ifStmt.elseBranch());
        if (ar == null) {
            return null;
        }
        return new StringMatch(strValue, ar.varName, ar.intValue);
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
    private SwitchStatement buildStringSwitch(HashCodeMatch hashMatch, TempSwitchMatch tempMatch,
                                              Expression discriminantOverride) {
        // 临时整数值 → 字符串字面量的映射(收集时已按 temp 去重,碰撞 case 全保留)
        Map<Integer, String> tempToString = hashMatch.tempToString;

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

        // 新判别式为原始字符串变量(而非 hashCode 的目标).
        // 若字符串变量是副本(String var2 = s;)且其声明已被丢弃,
        // 判别式替换为其初始值(switch (s)).
        Expression discriminant = discriminantOverride != null
                ? discriminantOverride : hashMatch.stringVar;
        return new SwitchStatement(discriminant, newCases);
    }

    /** hashCode-switch 模式的匹配结果,保存提取到的映射关系. */
    private static class HashCodeMatch {

        /** 字符串变量表达式 */
        final Expression stringVar;

        /** 临时整数值到字符串字面量的映射(碰撞 case 全部保留) */
        final LinkedHashMap<Integer, String> tempToString;

        /** 临时变量名称 */
        final String tempVarName;

        /** default case 分组(可能为 null) */
        final SwitchStatement.CaseGroup defaultCase;

        HashCodeMatch(Expression stringVar,
                      LinkedHashMap<Integer, String> tempToString,
                      String tempVarName,
                      SwitchStatement.CaseGroup defaultCase) {
            this.stringVar = stringVar;
            this.tempToString = tempToString;
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
