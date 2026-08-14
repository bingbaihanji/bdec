package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InstanceOfExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记录模式匹配重写器 — 将记录模式的字节码反编译模式折叠为
 * {@code instanceof RecordType(comp1, comp2)} 表达式.
 *
 * <p>严格遵循 Vineflower 的
 * {@code IfPatternMatchProcessor.identifyIfRecordPatternMatch()} 设计:
 *
 * <ol>
 *   <li>检测 {@code if (!(obj instanceof RecordType)) return;} 守卫模式</li>
 *   <li>在 else 体中识别强制转型 {@code RecordType v = (RecordType) obj}</li>
 *   <li>将组件提取调用({@code v.comp1()})与变量声明匹配,
 *       处理伪栈操作({@code exVar = stackCVar; realVar = exVar;})</li>
 *   <li>构建包含模式变量的 {@code instanceof RecordType(comp1, comp2)} 表达式</li>
 *   <li>移除重复的独立调用和死代码</li>
 * </ol>
 */
public class RecordPatternRewriter extends AstTransformer implements RewriteRule {

    /** 当前 rewrite() 调用的包名(规则实例按引擎持有,rewrite 内单线程设置). */
    private String currentPackage = "";

    /** 当前编译单元的内部类友好名称映射. */
    private java.util.Map<String, String> innerClassNames = java.util.Map.of();

    /** 本次 rewrite() 中由模式变量类型渲染收集的 import. */
    private Set<String> collectedImports = new HashSet<>();

    private static String simplifyTypeName(String internalName) {
        if (internalName == null) {
            return null;
        }
        int slash = internalName.lastIndexOf('/');
        if (slash >= 0) {
            return internalName.substring(slash + 1);
        }
        int dollar = internalName.lastIndexOf('$');
        if (dollar >= 0) {
            return internalName.substring(dollar + 1);
        }
        return internalName;
    }

    @Override
    public String name() {return "record-pattern";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        this.currentPackage = unit.packageName() != null ? unit.packageName() : "";
        this.innerClassNames = unit.innerClassNames();
        this.collectedImports = new HashSet<>();
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(),
                com.bingbaihanji.bdec.util.TypeText.mergeImports(
                        unit.imports(), collectedImports),
                types, unit.innerClassNames());
    }

    // ── 主检测逻辑 ──

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                AstNode transformed = visitStatement(md.body(), null);
                Statement newBody = transformed instanceof Statement s ? s : md.body();
                members.add(withBody(md, newBody instanceof BlockStatement bs ? bs : newBody));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 在 BlockStatement 级别检测记录模式:
     *  <pre>
     *  if (!(obj instanceof RecordType)) { return; }
     *  RecordType var = (RecordType) obj;
     *  var.comp1(); / Type1 t1 = var.comp1(); / Type1 name1 = t1;
     *  var.comp2(); / Type2 t2 = var.comp2(); / Type2 name2 = t2;
     *  </pre>
     *  参考 Vineflower: IfPatternMatchProcessor.checkBranch 在 if 体后检测提取模式.
     */
    @Override
    protected Statement transformBlock(BlockStatement s) {
        Statement result = super.transformBlock(s);
        if (!(result instanceof BlockStatement bs)) {
            return result;
        }

        List<Statement> stmts = bs.statements();
        if (stmts.isEmpty()) {
            return result;
        }

        // 在语句列表中扫描记录模式
        for (int i = 0; i < stmts.size(); i++) {
            if (!(stmts.get(i) instanceof IfStatement ifStmt)) {
                continue;
            }

            String[] pattern = matchIfGuard(ifStmt);
            if (pattern == null) {
                continue;
            }
            String testExprName = pattern[0];
            String recordTypeName = pattern[1];
            String mode = pattern[2];

            Statement replacement = null;
            int skipCount = 1; // 默认只跳过 if 语句本身

            if ("guard".equals(mode)) {
                // 提取代码在 if 之后(guard 模式)
                List<Statement> afterIf = new ArrayList<>();
                for (int j = i + 1; j < stmts.size(); j++) {
                    afterIf.add(stmts.get(j));
                }
                Statement recPat = buildRecordPatternFromBody(
                        new VarExpr(testExprName), recordTypeName, afterIf);
                if (recPat != null) {
                    skipCount = 1 + afterIf.size();
                    replacement = recPat;
                }
            } else if ("else-extract".equals(mode)) {
                // 提取代码在 else 体中: if (!(obj instanceof T)) { return; } else { cast+extract }
                List<Statement> elseStmts = flattenBlock(ifStmt.elseBranch());
                Statement recPat = buildRecordPatternFromBody(
                        new VarExpr(testExprName), recordTypeName, elseStmts);
                if (recPat != null) {
                    replacement = recPat;
                }
            } else if ("direct".equals(mode)) {
                // 提取代码在 then 体中(direct 模式)
                List<Statement> thenStmts = flattenBlock(ifStmt.thenBranch());
                Statement recPat = buildRecordPatternFromBody(
                        new VarExpr(testExprName), recordTypeName, thenStmts);
                if (recPat != null) {
                    replacement = recPat;
                }
            }

            if (replacement == null) {
                continue;
            }

            // 构建新的语句列表
            List<Statement> newStmts = new ArrayList<>();
            for (int k = 0; k < i; k++) {
                newStmts.add(stmts.get(k));
            }
            newStmts.add(replacement);
            // 添加剩余语句(guard 模式下跳过 extraction 语句)
            for (int k = i + skipCount; k < stmts.size(); k++) {
                newStmts.add(stmts.get(k));
            }
            return new BlockStatement(newStmts);
        }
        return result;
    }

    /** 检测 if 守卫:两种模式
     *  <ol>
     *  <li>if (!(obj instanceof RecordType)) { return; } (守卫,提取代码在后面)</li>
     *  <li>if (obj instanceof RecordType) { extraction... } (提取在 then 体中)</li>
     *  </ol>
     *  条件可以是 InstanceOfExpr 或 BinExpr(INSTANCEOF, left, right).
     *  @return [testExpressionName, recordTypeName, "guard"|"direct"] 或 null */
    private String[] matchIfGuard(IfStatement ifStmt) {
        Expression cond = ifStmt.condition();

        // 从条件中提取测试表达式和类型名
        InstanceofMatch match = extractInstanceofMatch(cond);
        if (match == null) {
            return null;
        }

        Expression testExpr = match.testExpr;
        String recordTypeName = match.typeName;
        boolean isNegated = isNegatedInstanceof(cond);

        String mode;
        if (isNegated) {
            // 模式A1: if (!(obj instanceof T)) { return; } — 守卫,提取在后
            if (ifStmt.elseBranch() == null) {
                if (!isJustReturn(ifStmt.thenBranch())) {
                    return null;
                }
                mode = "guard";
            }
            // 模式A2: if (!(obj instanceof T)) { return; } else { extraction... }
            else if (isJustReturn(ifStmt.thenBranch())) {
                mode = "else-extract";
            } else {
                return null;
            }
        } else {
            // 模式B: if (obj instanceof T) { extraction... } [else { return; }]
            if (ifStmt.elseBranch() != null && !isJustReturn(ifStmt.elseBranch())) {
                return null;
            }
            mode = "direct";
        }

        String testExprName = testExpr instanceof VarExpr v ? v.name() : "obj";
        return new String[]{testExprName, recordTypeName, mode};
    }

    /** 从条件表达式中提取 instanceof 的测试表达式和类型名 */
    private InstanceofMatch extractInstanceofMatch(Expression cond) {
        if (cond == null) {
            return null;
        }

        // BinExpr(INSTANCEOF, left, right) — right 为 VarExpr(类型名)
        if (cond instanceof BinExpr be && be.operator() == BinaryOperator.INSTANCEOF
                && be.right() instanceof VarExpr typeVar) {
            return new InstanceofMatch(be.left(), typeVar.name());
        }

        // InstanceOfExpr
        if (cond instanceof InstanceOfExpr ioe) {
            return new InstanceofMatch(ioe.operand(),
                    simplifyTypeName(ioe.targetType().internalName()));
        }

        // UnExpr(NOT, ...) — 递归检查内部
        if (cond instanceof UnExpr un && un.operator() == UnaryOperator.NOT) {
            return extractInstanceofMatch(un.operand());
        }

        return null;
    }

    /** 条件是否被否定: !(obj instanceof T) */
    private boolean isNegatedInstanceof(Expression cond) {
        if (cond instanceof UnExpr un && un.operator() == UnaryOperator.NOT) {
            Expression inner = un.operand();
            return inner instanceof BinExpr be
                    && be.operator() == BinaryOperator.INSTANCEOF
                    || inner instanceof InstanceOfExpr;
        }
        return false;
    }

    // ── 记录模式构建(遵循 Vineflower identifyIfRecordPatternMatch) ──

    /**
     * 在给定体中检测完整的记录组件提取模式.
     *
     * <p>Vineflower 模式:
     * <pre>
     *   RecordType varN = (RecordType) expr;       // 强制转型
     *   [varN.comp1()]                             // 提取尝试(可能重复)
     *   Type1 temp1 = varN.comp1();                // 组件调用 + 存储
     *   Type1 comp1name = temp1;                    // 伪栈操作
     *   [varN.comp2()]                             // 提取尝试
     *   Type2 temp2 = varN.comp2();                // 组件调用 + 存储(可能重用 temp1)
     *   Type2 comp2name = temp2;
     *   ...用户代码...
     * </pre>
     */
    private Statement buildRecordPatternFromBody(Expression testExpr,
                                                 String recordTypeName,
                                                 List<Statement> bodyStmts) {
        if (bodyStmts.isEmpty()) {
            return null;
        }

        // 展开常量真条件的 if(javac 的 null 检查解析为 1 != 0),
        // 其 then 体(组件委托赋值 + 用户代码)应视为直接语句
        List<Statement> stmts = normalizeConstantIfs(bodyStmts);

        // 第一步:查找强制转型 RecordType varN = (RecordType) expr;
        int idx = 0;
        String castVarName = null;
        if (idx < stmts.size()) {
            castVarName = tryMatchCast(stmts.get(idx), recordTypeName, testExpr);
            if (castVarName != null) {
                idx++;
            }
        }
        if (castVarName == null) {
            return null;
        }

        // 第二步:收集组件调用:逐个匹配 varN.compM() → tempVar → realVar
        // Vineflower getChildPattern: 迭代每个记录组件,跳过独立调用,匹配赋值链
        List<ComponentMatch> components = new ArrayList<>();
        int scanIdx = idx;

        while (scanIdx < stmts.size()) {
            // 跳过独立的 varN.comp() 调用(Vineflower 提取尝试残留)
            if (isStandaloneComponentCall(stmts.get(scanIdx), castVarName)) {
                scanIdx++;
                continue;
            }

            // 尝试检测组件声明: Type temp = varN.comp();
            ComponentMatch cm = tryMatchComponent(stmts, scanIdx, castVarName);
            if (cm == null) {
                break; // 找不到更多组件,进入用户代码
            }

            components.add(cm);
            scanIdx = cm.endIdx;
        }

        if (components.isEmpty()) {
            return null;
        }

        // 第三步:构建 instanceof RecordType(comp1, comp2) 条件
        // 并将 if 的 then 体替换为剩余的用户代码
        StringBuilder patternStr = new StringBuilder(recordTypeName);
        patternStr.append("(");
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                patternStr.append(", ");
            }
            ComponentMatch cm = components.get(i);
            // 模式变量类型用 import 感知的短名渲染并收集缺失 import,
            // 避免输出 instanceof R(Box<java.util.Map<...>> b) 全限定名
            patternStr.append(com.bingbaihanji.bdec.util.TypeText.render(
                    cm.type(), currentPackage, innerClassNames, collectedImports))
                    .append(" ").append(cm.name());
        }
        patternStr.append(")");

        // 构建新的条件表达式: obj instanceof RecordType(comp1, comp2)
        // 使用 BinExpr(INSTANCEOF, testExpr, VarExpr(patternStr))
        Expression newCondition = new BinExpr(BinaryOperator.INSTANCEOF,
                testExpr, new VarExpr(patternStr.toString()));

        // 构建新的 then 体:从 cast 后,组件提取后的代码
        List<Statement> cleanedThen = new ArrayList<>();
        for (int i = scanIdx; i < stmts.size(); i++) {
            Statement stmt = stmts.get(i);
            // 跳过死代码(if (0==1) 等)和重复声明
            if (isDeadCode(stmt)) {
                continue;
            }
            cleanedThen.add(stmt);
        }
        // 移除重复的变量声明(var6, name 等)
        cleanedThen = removeDuplicateDecls(cleanedThen, components);

        Statement newThen = cleanedThen.isEmpty()
                ? new BlockStatement(List.of())
                : cleanedThen.size() == 1 ? cleanedThen.get(0)
                : new BlockStatement(cleanedThen);

        return new IfStatement(newCondition, newThen, null);
    }

    // ── 辅助:类型匹配 ──

    /** 将常量真条件的 if 展开为其 then 体.
     *  <p>javac 的记录模式 null 检查(if (obj != null))在反编译后
     *  可能呈现为 if (1 != 0) 形式,其 then 体包含组件委托赋值与
     *  用户代码,应视为直接语句以便组件匹配和死代码移除.</p> */
    private List<Statement> normalizeConstantIfs(List<Statement> stmts) {
        List<Statement> result = new ArrayList<>();
        for (Statement s : stmts) {
            if (s instanceof IfStatement i && isConstantTrue(i.condition())) {
                result.addAll(flattenBlock(i.thenBranch()));
            } else {
                result.add(s);
            }
        }
        return result;
    }

    /** 检查条件是否为常量真(两侧均为字面量的比较) */
    private boolean isConstantTrue(Expression e) {
        if (e instanceof BinExpr be
                && be.left() instanceof LitExpr l
                && be.right() instanceof LitExpr r
                && l.value() instanceof Number a
                && r.value() instanceof Number b) {
            return switch (be.operator()) {
                case EQ -> a.intValue() == b.intValue();
                case NE -> a.intValue() != b.intValue();
                case LT -> a.intValue() < b.intValue();
                case GT -> a.intValue() > b.intValue();
                case LE -> a.intValue() <= b.intValue();
                case GE -> a.intValue() >= b.intValue();
                default -> false;
            };
        }
        return false;
    }

    /** 检测强制转型: RecordType varName = (RecordType) expr;
     *  可作为 VariableDeclaration 或 ExpressionStatement(赋值)出现 */
    private String tryMatchCast(Statement s, String recordTypeName, Expression testExpr) {
        Expression initializer = null;
        String varName = null;

        if (s instanceof VariableDeclaration vd) {
            initializer = vd.initializer();
            varName = vd.name();
        } else if (s instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.target() instanceof VarExpr var) {
            initializer = assign.value();
            varName = var.name();
        }

        if (initializer == null || varName == null) {
            return null;
        }
        if (!(initializer instanceof CastExpr cast)) {
            return null;
        }

        String castType = simplifyTypeName(cast.targetType().internalName());
        if (!recordTypeName.equals(castType)) {
            return null;
        }

        return varName;
    }

    /** 检测组件声明:Type temp = castVar.comp(); → Type real = temp;
     *  可以是 VariableDeclaration 或 ExpressionStatement(AssignExpr) */
    private ComponentMatch tryMatchComponent(List<Statement> stmts, int idx,
                                             String castVarName) {
        if (idx >= stmts.size()) {
            return null;
        }

        // 从语句中提取初始值表达式和变量名
        Expression initializer = null;
        String tempName = null;
        com.bingbaihanji.bdec.type.JavaType tempType = null;

        Statement first = stmts.get(idx);
        if (first instanceof VariableDeclaration vd) {
            initializer = vd.initializer();
            tempName = vd.name();
            tempType = vd.type();
        } else if (first instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.target() instanceof VarExpr tv) {
            initializer = assign.value();
            tempName = tv.name();
        }

        if (initializer == null || tempName == null) {
            return null;
        }

        // 检查初始值:castVar.comp()
        String compName = tryMatchComponentCall(initializer, castVarName);
        if (compName == null) {
            return null;
        }

        String realName = tempName;
        com.bingbaihanji.bdec.type.JavaType realType = tempType;
        int endIdx = idx + 1;

        // Vineflower pseudo stack: 检查后续语句是否为 realVar = tempVar 的
        // 委托链(槽位复用会产生多跳,如 var6 → var7 → age).
        // 委托的初始化器可能引用链上的任意一个临时变量,
        // 因此按已见名称集合匹配,沿链追踪到最终的实际变量名.
        Set<String> chainNames = new HashSet<>();
        chainNames.add(tempName);
        while (endIdx < stmts.size()) {
            Statement next = stmts.get(endIdx);
            String delegated = tryMatchDelegateAssignAny(next, chainNames);
            if (delegated == null) {
                break;
            }
            realName = delegated;
            if (next instanceof VariableDeclaration nvd) {
                realType = nvd.type();
            }
            chainNames.add(delegated);
            endIdx++;
        }

        return new ComponentMatch(realName, realType, endIdx);
    }

    /** 检查表达式是否为 castVar.comp() 形式的调用 */
    private String tryMatchComponentCall(Expression e, String castVarName) {
        if (!(e instanceof InvocationExpr inv)) {
            return null;
        }
        if (!(inv.target() instanceof VarExpr target)) {
            return null;
        }
        if (!castVarName.equals(target.name())) {
            return null;
        }
        // 方法名即为记录组件名
        return inv.methodName();
    }

    /** 检测伪栈委托: realVar = tempVar; (Vineflower exVar = stackCVar) */
    private String tryMatchDelegateAssign(Statement s, String tempName) {
        return tryMatchDelegateAssignAny(s, Set.of(tempName));
    }

    /** 检测伪栈委托:初始化器为已见临时变量集合中任一名称的赋值/声明 */
    private String tryMatchDelegateAssignAny(Statement s, Set<String> chainNames) {
        if (s instanceof VariableDeclaration vd) {
            if (vd.initializer() instanceof VarExpr iv && chainNames.contains(iv.name())) {
                return vd.name();
            }
        } else if (s instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign) {
            if (assign.target() instanceof VarExpr tv
                    && assign.value() instanceof VarExpr rv
                    && chainNames.contains(rv.name())) {
                return tv.name();
            }
        }
        return null;
    }

    /** 检查语句是否仅为 return;(Vineflower isStatementMatchThrow) */
    private boolean isJustReturn(Statement s) {
        if (s instanceof ReturnStatement) {
            return true;
        }
        if (s instanceof BlockStatement bs
                && bs.statements().size() == 1
                && bs.statements().get(0) instanceof ReturnStatement) {
            return true;
        }
        return false;
    }

    // ── 辅助:语句分析 ──

    /** 检查是否为独立组件调用:castVar.comp() 且未赋值 */
    private boolean isStandaloneComponentCall(Statement s, String castVarName) {
        if (!(s instanceof ExpressionStatement es)) {
            return false;
        }
        return tryMatchComponentCall(es.expression(), castVarName) != null;
    }

    /** 死代码检测:if (0==1) 或 if (1==0) 等不可达代码 */
    private boolean isDeadCode(Statement s) {
        if (s instanceof IfStatement i
                && i.condition() instanceof BinExpr be
                && be.operator() == BinaryOperator.EQ) {
            return isZero(be.left()) || isZero(be.right());
        }
        return false;
    }

    private boolean isZero(Expression e) {
        return e instanceof LitExpr lit && lit.value() instanceof Number n
                && n.intValue() == 0;
    }

    /** 将语句扁平化为列表(Vineflower 的 basic block 概念) */
    private List<Statement> flattenBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new ArrayList<>(bs.statements());
        }
        List<Statement> result = new ArrayList<>();
        result.add(s);
        return result;
    }

    /** 移除与组件同名的重复声明 */
    private List<Statement> removeDuplicateDecls(List<Statement> stmts,
                                                 List<ComponentMatch> components) {
        Set<String> compNames = new HashSet<>();
        for (ComponentMatch cm : components) {
            compNames.add(cm.name());
        }
        List<Statement> filtered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Statement s : stmts) {
            if (s instanceof VariableDeclaration vd) {
                String n = vd.name();
                // 跳过重复声明和与组件同名的声明
                if (!seen.add(n) || compNames.contains(n)) {
                    // 若为赋值则保留
                    if (vd.initializer() != null && !compNames.contains(n)) {
                        filtered.add(new ExpressionStatement(
                                new AssignExpr(new VarExpr(n), vd.initializer())));
                    }
                    continue;
                }
            }
            filtered.add(s);
        }
        return filtered;
    }

    // 提取 instanceof 条件的临时结果
    private static class InstanceofMatch {

        final Expression testExpr;

        final String typeName;

        InstanceofMatch(Expression e, String t) {
            this.testExpr = e;
            this.typeName = t;
        }
    }

    // ── 工具方法 ──

    private record ComponentMatch(
            String name, com.bingbaihanji.bdec.type.JavaType type,
            int endIdx) {}
}
