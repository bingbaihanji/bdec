package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;

import java.util.*;

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

    @Override
    public String name() {return "record-pattern";}

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
                AstNode transformed = visitStatement(md.body(), null);
                Statement newBody = transformed instanceof Statement s ? s : md.body();
                members.add(new MethodDeclaration(md.accessFlags(), md.name(),
                        md.returnType(), md.parameterNames(), md.parameterTypes(),
                        md.typeParameters(),
                        newBody instanceof BlockStatement bs ? bs : newBody));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    // ── 主检测逻辑 ──

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
        if (!(result instanceof BlockStatement bs)) return result;

        List<Statement> stmts = bs.statements();
        if (stmts.isEmpty()) return result;

        // 在语句列表中扫描记录模式
        for (int i = 0; i < stmts.size(); i++) {
            if (!(stmts.get(i) instanceof IfStatement ifStmt)) continue;

            String[] pattern = matchIfGuard(ifStmt);
            if (pattern == null) continue;
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
                    // 构建: if (obj instanceof RecordType(comps)) { userCode }
                    // recPat 是新的 IfStatement,替换 if+extraction
                    // 跳过 guard if 及其后的 extraction 语句
                    skipCount = 1 + afterIf.size(); // 跳过所有
                    // recPat 本身含 then 体(用户代码)
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

            if (replacement == null) continue;

            // 构建新的语句列表
            List<Statement> newStmts = new ArrayList<>();
            for (int k = 0; k < i; k++) newStmts.add(stmts.get(k));
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
     *  @return [testExpressionName, recordTypeName, "guard"|"direct"] 或 null */
    private String[] matchIfGuard(IfStatement ifStmt) {
        Expression cond = ifStmt.condition();
        Expression testExpr = null;
        String recordTypeName = null;
        String mode = null;

        // 模式A: !(obj instanceof RecordType) + return → 提取代码在 if 之后
        if (cond instanceof UnExpr un && un.operator() == UnaryOperator.NOT
                && un.operand() instanceof InstanceOfExpr ioe) {
            if (ifStmt.elseBranch() != null) return null;
            if (!isJustReturn(ifStmt.thenBranch())) return null;
            testExpr = ioe.operand();
            recordTypeName = simplifyTypeName(ioe.targetType().internalName());
            mode = "guard";
        }
        // 模式B: obj instanceof RecordType + 提取代码在 then 体中
        if (cond instanceof InstanceOfExpr ioe) {
            if (ifStmt.elseBranch() != null) {
                // 检查 else 是否仅为 return
                if (!isJustReturn(ifStmt.elseBranch())) return null;
            }
            testExpr = ioe.operand();
            recordTypeName = simplifyTypeName(ioe.targetType().internalName());
            mode = "direct";
        }

        if (testExpr == null || recordTypeName == null || mode == null) return null;

        String testExprName = testExpr instanceof VarExpr v ? v.name() : "obj";
        return new String[]{testExprName, recordTypeName, mode};
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
        if (bodyStmts.isEmpty()) return null;

        // 第一步:查找强制转型 RecordType varN = (RecordType) expr;
        int idx = 0;
        String castVarName = null;
        if (idx < bodyStmts.size()) {
            castVarName = tryMatchCast(bodyStmts.get(idx), recordTypeName, testExpr);
            if (castVarName != null) idx++;
        }
        if (castVarName == null) return null;

        // 第二步:收集组件调用:逐个匹配 varN.compM() → tempVar → realVar
        // Vineflower getChildPattern: 迭代每个记录组件,跳过独立调用,匹配赋值链
        List<ComponentMatch> components = new ArrayList<>();
        int scanIdx = idx;

        while (scanIdx < bodyStmts.size()) {
            // 跳过独立的 varN.comp() 调用(Vineflower 提取尝试残留)
            if (isStandaloneComponentCall(bodyStmts.get(scanIdx), castVarName)) {
                scanIdx++;
                continue;
            }

            // 尝试检测组件声明: Type temp = varN.comp();
            ComponentMatch cm = tryMatchComponent(bodyStmts, scanIdx, castVarName);
            if (cm == null) break; // 找不到更多组件,进入用户代码

            components.add(cm);
            scanIdx = cm.endIdx;
        }

        if (components.isEmpty()) return null;

        // 第三步:构建 instanceof RecordType(comp1, comp2) 条件
        // 并将 if 的 then 体替换为剩余的用户代码
        StringBuilder patternStr = new StringBuilder(recordTypeName);
        patternStr.append("(");
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) patternStr.append(", ");
            ComponentMatch cm = components.get(i);
            patternStr.append(cm.type().displayName()).append(" ").append(cm.name());
        }
        patternStr.append(")");

        // 构建新的条件表达式: obj instanceof RecordType(comp1, comp2)
        // 使用 BinExpr(INSTANCEOF, testExpr, VarExpr(patternStr))
        Expression newCondition = new BinExpr(BinaryOperator.INSTANCEOF,
                testExpr, new VarExpr(patternStr.toString()));

        // 构建新的 then 体:从 cast 后,组件提取后的代码
        List<Statement> cleanedThen = new ArrayList<>();
        for (int i = scanIdx; i < bodyStmts.size(); i++) {
            Statement stmt = bodyStmts.get(i);
            // 跳过死代码(if (0==1) 等)和重复声明
            if (isDeadCode(stmt)) continue;
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

    private record ComponentMatch(
            String name, com.bingbaihanji.bdec.type.JavaType type,
            int endIdx) {}

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

        if (initializer == null || varName == null) return null;
        if (!(initializer instanceof CastExpr cast)) return null;

        String castType = simplifyTypeName(cast.targetType().internalName());
        if (!recordTypeName.equals(castType)) return null;

        return varName;
    }

    /** 检测组件声明:Type temp = castVar.comp(); → Type real = temp; */
    private ComponentMatch tryMatchComponent(List<Statement> stmts, int idx,
                                              String castVarName) {
        if (idx >= stmts.size()) return null;

        Statement first = stmts.get(idx);
        if (!(first instanceof VariableDeclaration vd)) return null;
        if (vd.initializer() == null) return null;

        // 检查初始值:castVar.comp()
        String compName = tryMatchComponentCall(vd.initializer(), castVarName);
        if (compName == null) return null;

        String realName = vd.name();
        com.bingbaihanji.bdec.type.JavaType realType = vd.type();
        int endIdx = idx + 1;

        // Vineflower pseudo stack: 检查下一个语句是否为 realVar = tempVar;
        // 其中 tempVar 仅在此处使用
        if (endIdx < stmts.size()) {
            Statement next = stmts.get(endIdx);
            String delegated = tryMatchDelegateAssign(next, vd.name());
            if (delegated != null) {
                // 使用真实变量名和类型
                realName = delegated;
                // 从 VariableDeclaration 获取真实类型
                if (next instanceof VariableDeclaration nvd) {
                    realType = nvd.type();
                }
                endIdx = idx + 2;
            }
        }

        return new ComponentMatch(realName, realType, endIdx);
    }

    /** 检查表达式是否为 castVar.comp() 形式的调用 */
    private String tryMatchComponentCall(Expression e, String castVarName) {
        if (!(e instanceof InvocationExpr inv)) return null;
        if (!(inv.target() instanceof VarExpr target)) return null;
        if (!castVarName.equals(target.name())) return null;
        // 方法名即为记录组件名
        return inv.methodName();
    }

    /** 检测伪栈委托: realVar = tempVar; (Vineflower exVar = stackCVar) */
    private String tryMatchDelegateAssign(Statement s, String tempName) {
        if (s instanceof VariableDeclaration vd) {
            if (vd.initializer() instanceof VarExpr iv && tempName.equals(iv.name())) {
                return vd.name();
            }
        } else if (s instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign) {
            if (assign.target() instanceof VarExpr tv
                    && assign.value() instanceof VarExpr rv
                    && tempName.equals(rv.name())) {
                return tv.name();
            }
        }
        return null;
    }

    // ── 辅助:语句分析 ──

    /** 检查语句是否仅为 return;(Vineflower isStatementMatchThrow) */
    private boolean isJustReturn(Statement s) {
        if (s instanceof ReturnStatement) return true;
        if (s instanceof BlockStatement bs
                && bs.statements().size() == 1
                && bs.statements().get(0) instanceof ReturnStatement) return true;
        return false;
    }

    /** 检查是否为独立组件调用:castVar.comp() 且未赋值 */
    private boolean isStandaloneComponentCall(Statement s, String castVarName) {
        if (!(s instanceof ExpressionStatement es)) return false;
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

    // ── 工具方法 ──

    private static String simplifyTypeName(String internalName) {
        if (internalName == null) return null;
        int slash = internalName.lastIndexOf('/');
        if (slash >= 0) return internalName.substring(slash + 1);
        int dollar = internalName.lastIndexOf('$');
        if (dollar >= 0) return internalName.substring(dollar + 1);
        return internalName;
    }
}
