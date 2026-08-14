package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 源码清理重写器,作为最小安全网修复反编译产生的编译错误.
 * <p>
 * 仅修复会导致编译错误的模式,包括:
 * </p>
 * <ol>
 *   <li>包装在 return 语句中的 void 方法调用 —— 拆分为独立调用加默认值返回</li>
 *   <li>throw 语句中未声明的异常变量 —— 自动声明为 Throwable 类型</li>
 *   <li>同一作用域内的重复变量声明 —— 转换为赋值语句</li>
 *   <li>未声明的局部变量 —— 使用安全的默认值自动声明,避免字段遮蔽</li>
 * </ol>
 */
public class SourceCleanup implements RewriteRule {

    @Override
    public String name() {return "source-cleanup";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext ctx) {
        // 收集 record 组件名(作为字段名防止自动声明时遮蔽)
        Set<String> extraFields = new HashSet<>();
        if (ctx.classFile() != null) {
            for (var rc : ctx.classFile().recordComponents()) {
                if (rc.name() != null) {
                    extraFields.add(rc.name());
                }
            }
        }
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(cleanupType(td, extraFields));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 清理单个类型声明中的所有方法体,修复常见的编译错误模式.
     *
     * @param td 待清理的类型声明
     * @return 清理后的类型声明
     */
    private TypeDeclaration cleanupType(TypeDeclaration td, Set<String> extraFields) {
        Set<String> fieldNames = SourceCleanupSupport.collectFieldNames(td);
        fieldNames.addAll(extraFields); // 添加 record 组件名
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                boolean nonVoid = md.returnType() != null
                        && md.returnType().kind() != TypeKind.VOID;
                Set<String> paramNames = new HashSet<>(java.util.Arrays.asList(md.parameterNames()));
                members.add(withBody(md, fix(md.body(), nonVoid, md.returnType(), fieldNames, paramNames)));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /**
     * 递归修复语句中的编译错误模式.
     *
     * @param s         待修复的语句
     * @param nonVoid   方法是否有非 void 返回类型
     * @param retType   方法的返回类型
     * @param fieldNames 当前类的字段名集合,用于避免字段遮蔽
     * @param paramNames 方法参数名集合,用于避免重复声明
     * @return 修复后的语句
     */
    private Statement fix(Statement s, boolean nonVoid, JavaType retType,
                          Set<String> fieldNames, Set<String> paramNames) {
        if (s == null) {
            return null;
        }
        // 修复模式0:块外的独立语句(如直接作为 try 体的单语句)
        // 同样检查未声明的变量引用并自动声明.
        // BlockStatement 分支只处理块内的语句,单语句 try 体会绕过该检查.
        if (s instanceof ExpressionStatement || s instanceof ThrowStatement
                || (s instanceof ReturnStatement rs && rs.value() != null)) {
            Set<String> used = new HashSet<>();
            collectVarNames(s, used);
            List<Statement> pre = new ArrayList<>();
            for (String u : used) {
                if (!fieldNames.contains(u) && !paramNames.contains(u)
                        && !isBuiltin(u) && !SourceCleanupSupport.looksLikeClassName(u)) {
                    pre.add(new VariableDeclaration(JavaType.INT, u,
                            new LitExpr(0, JavaType.INT)));
                }
            }
            if (!pre.isEmpty()) {
                List<Statement> combined = new ArrayList<>(pre);
                combined.add(s);
                return new BlockStatement(combined);
            }
        }
        // 修复模式1:非 void 方法中的 return void 方法调用 → 拆分为调用 + 默认值 return
        switch (s) {
            case ReturnStatement rs when rs.value() != null && nonVoid -> {
                Expression v = rs.value();
                if (v instanceof InvocationExpr inv && isVoid(inv)) {
                    return new BlockStatement(List.of(
                            new ExpressionStatement(v),
                            new ReturnStatement(defaultVal(retType))));
                }
                return s;
            }

            // 修复模式2:throw varN 且变量未声明 → 自动声明 Throwable 类型变量
            case ThrowStatement ts when ts.expression() instanceof VarExpr ve && ve.name().startsWith("var") -> {
                return new BlockStatement(List.of(
                        new VariableDeclaration(JavaType.classType("java/lang/Throwable"),
                                ve.name(), null), s));
            }
            case BlockStatement bs -> {
                // 截断 return/throw 之后的死代码(记录模式去糖化后可能遗留)
                List<Statement> stmts = SourceCleanupSupport.truncateAfterTerminator(bs.statements());
                // 扁平化纯顺序的嵌套块(仅声明与表达式语句,无控制流):
                // BlockReducer 按组输出时会把预置头声明包装为独立块
                // (如 try 体内 { int transferCount = ...; int i = 0; }),
                // 后续语句(while 循环等)在块外引用这些声明——
                // 嵌套块是独立作用域,必须扁平化到父级才能被后续语句看见,
                // 否则自动声明逻辑会生成重复/错误的 "int i = 0;".
                stmts = SourceCleanupSupport.flattenPlainNestedBlocks(stmts);
                // 提升 if-else 两分支共同声明的变量到 if 之前(条件赋值的菱形合并):
                // 两分支各自声明同名变量时作用域局限在分支内,合并点后的引用
                // 会触发下方 "int y = 0" 误补(语义错误).提升为 "Type y;" 前置,
                // 分支内声明转为赋值,使 y 成为块级声明.
                stmts = hoistConditionalDecls(stmts);

                List<Statement> cleaned = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                // 第一趟:收集当前块(含嵌套块)中所有已声明的变量名
                Set<String> allDeclared = new HashSet<>();
                collectDeclared(new BlockStatement(stmts), allDeclared);
                // 将字段名也加入已声明集合,避免自动声明时遮蔽字段
                allDeclared.addAll(fieldNames);
                // 将参数名也加入已声明集合,避免重复声明参数
                allDeclared.addAll(paramNames);
                for (Statement c : stmts) {
                    // 修复模式3:重复变量声明 → 转换为赋值语句
                    if (c instanceof VariableDeclaration vd) {
                        if (!seen.add(vd.name()) && vd.initializer() != null) {
                            cleaned.add(new ExpressionStatement(
                                    new AssignExpr(new VarExpr(vd.name()), vd.initializer())));
                            continue;
                        }
                    }
                    // 修复模式4:在语句执行前检查是否存在未声明的变量引用
                    Set<String> used = new HashSet<>();
                    collectVarNames(c, used);
                    for (String u : used) {
                        if (!allDeclared.contains(u) && !seen.contains(u)
                                && !isBuiltin(u) && !SourceCleanupSupport.looksLikeClassName(u)) {
                            // 使用默认值自动声明(整型默认为 0,对象默认为 null)
                            cleaned.add(new VariableDeclaration(
                                    JavaType.INT, u,
                                    new LitExpr(0, JavaType.INT)));
                            seen.add(u);
                            allDeclared.add(u);
                        }
                    }
                    // 将已声明变量合并到字段集合,防止 fix 内部重复声明
                    Set<String> effectiveFields = new HashSet<>(fieldNames);
                    effectiveFields.addAll(seen);
                    effectiveFields.addAll(allDeclared);
                    cleaned.add(fix(c, nonVoid, retType, effectiveFields, paramNames));
                }
                return new BlockStatement(foldSyntheticTemps(cleaned));
            }
            case IfStatement i -> {
                // instanceof 模式变量(如 obj instanceof RecordDemo(String name, int age))
                // 已在模式中声明,自动声明逻辑必须知晓,否则会生成重复的 "int name = 0"
                Set<String> patternVars = new HashSet<>();
                if (i.condition() != null) {
                    collectPatternVars(i.condition(), patternVars);
                }
                Set<String> branchDeclared = new HashSet<>(fieldNames);
                branchDeclared.addAll(patternVars);
                return new IfStatement(i.condition(),
                        fix(i.thenBranch(), nonVoid, retType, branchDeclared, paramNames),
                        i.elseBranch() != null
                                ? fix(i.elseBranch(), nonVoid, retType, branchDeclared, paramNames) : null);
            }
            case LoopStatement l -> {
                // 检查循环条件中的未声明变量(仅 WHILE/DO_WHILE;
                // FOR_EACH 的迭代变量是 for-each 语法的一部分)
                List<Statement> preStmts = new ArrayList<>();
                if (l.loopKind() == LoopStatement.LoopKind.WHILE
                        || l.loopKind() == LoopStatement.LoopKind.DO_WHILE) {
                    Set<String> condVars = new HashSet<>();
                    if (l.condition() != null) {
                        collectVarNamesInExpr(l.condition(), condVars);
                    }
                    for (String cv : condVars) {
                        if (!fieldNames.contains(cv) && !paramNames.contains(cv)
                                && !isBuiltin(cv) && !SourceCleanupSupport.looksLikeClassName(cv)) {
                            preStmts.add(new VariableDeclaration(JavaType.INT, cv,
                                    new LitExpr(0, JavaType.INT)));
                        }
                    }
                }
                // for-each 的迭代变量由循环语法声明,自动声明逻辑必须知晓
                Set<String> loopDeclared = fieldNames;
                if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH
                        && l.forEachVar() instanceof VarExpr fv) {
                    loopDeclared = new HashSet<>(fieldNames);
                    loopDeclared.add(fv.name());
                }
                Statement fixedBody = fix(l.body(), nonVoid, retType, loopDeclared, paramNames);
                LoopStatement newLoop = withLoopBody(l, fixedBody);
                if (preStmts.isEmpty()) {
                    return newLoop;
                }
                List<Statement> combined = new ArrayList<>(preStmts);
                combined.add(newLoop);
                return new BlockStatement(combined);
            }
            case TryStatement t -> {
                // 资源变量在 try(...) 中声明,其作用域覆盖 try 体/catch/finally,
                // 必须纳入已声明集合,否则自动声明逻辑会误生成 "int r = 0"
                Set<String> resourceParams = new HashSet<>(paramNames);
                for (TryStatement.Resource r : t.resources()) {
                    resourceParams.add(r.varName());
                }
                List<TryStatement.CatchClause> cc = new ArrayList<>();
                for (var c : t.catchClauses()) {
                    // 将 catch 子句的异常变量名纳入已声明集合,
                    // 防止自动声明逻辑在 catch 体内重复声明该变量
                    Set<String> extParams = new HashSet<>(resourceParams);
                    extParams.add(c.varName());
                    cc.add(new TryStatement.CatchClause(c.exceptionType(), c.varName(),
                            fix(c.body(), nonVoid, retType, fieldNames, extParams)));
                }
                return new TryStatement(fix(t.tryBody(), nonVoid, retType, fieldNames, resourceParams), cc,
                        t.finallyBody() != null
                                ? fix(t.finallyBody(), nonVoid, retType, fieldNames, resourceParams) : null,
                        t.resources());
            }
            default -> {
            }
        }
        return s;
    }

    /**
     * 折叠合成的临时变量(模式:Type varN = v; return varN; → return v;).
     *
     * <p>javac 对 try-with-resources 中的 return 会引入合成临时变量保存返回值
     * (在 finally 之前存储,finally 之后返回),反编译后呈现为
     * {@code Type varN = v; return varN;}.该变量是合成的(名称形如 var+数字),
     * 且仅在此 return 中使用,可安全内联.</p>
     *
     * @param stmts 待折叠的语句列表
     * @return 折叠后的语句列表
     */
    private List<Statement> foldSyntheticTemps(List<Statement> stmts) {
        List<Statement> result = new ArrayList<>();
        int i = 0;
        while (i < stmts.size()) {
            Statement s = stmts.get(i);
            if (s instanceof VariableDeclaration vd
                    && vd.initializer() instanceof VarExpr init
                    && isSyntheticTemp(vd.name())
                    && i + 1 < stmts.size()
                    && stmts.get(i + 1) instanceof ReturnStatement rs
                    && rs.value() instanceof VarExpr ret && ret.name().equals(vd.name())) {
                // 折叠:Type varN = v; return varN; → return v;
                result.add(new ReturnStatement(init));
                i += 2;
                continue;
            }
            result.add(s);
            i++;
        }
        return result;
    }

    /**
     * 判断变量名是否为合成的临时变量(形如 var+数字,如 var4/var10).
     *
     * @param name 待判断的变量名
     * @return 若为合成临时变量名则返回 {@code true}
     */
    private boolean isSyntheticTemp(String name) {
        if (name == null || name.length() < 4 || !name.startsWith("var")) {
            return false;
        }
        for (int i = 3; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断方法调用表达式是否为 void 返回类型.
     *
     * @param inv 方法调用表达式
     * @return 若返回类型为 void 则返回 {@code true}
     */
    private boolean isVoid(InvocationExpr inv) {
        return inv.returnType() != null && inv.returnType().kind() == TypeKind.VOID;
    }

    /**
     * 收集当前作用域中直接声明的变量名.
     *
     * <p>仅收集 BlockStatement 的直接 VariableDeclaration 子节点.
     * 不递归进入嵌套控制流结构(try/if/loop 体)中的声明,
     * 因为这些声明在 Java 作用域规则下对外部不可见,
     * 递归的 fix() 调用会独立处理嵌套作用域.
     */
    private void collectDeclared(Statement s, Set<String> out) {
        if (s instanceof VariableDeclaration vd) {
            out.add(vd.name());
        } else if (s instanceof BlockStatement bs) {
            for (Statement c : bs.statements()) {
                if (c instanceof VariableDeclaration vd) {
                    out.add(vd.name());
                }
                // 不递归进入嵌套的 if/loop/try 体
            }
        }
    }

    /**
     * 提升 if-else 两分支共同声明的变量到 if 之前(条件赋值的菱形合并).
     *
     * <p>模式:{@code if (c) { ...; T y = a; } else { ...; T y = b; }} 后接 y 的引用.
     * BlockReducer 按分支独立作用域翻译,导致 y 在两分支各声明一次(作用域局限于分支),
     * 合并点后的引用 y 未声明,触发自动声明 {@code int y = 0}(语义错误).
     * 此处将声明提升为 {@code T y;} 前置,分支内声明转为赋值 {@code y = a;}.</p>
     */
    private List<Statement> hoistConditionalDecls(List<Statement> stmts) {
        List<Statement> out = new ArrayList<>();
        for (Statement c : stmts) {
            if (c instanceof IfStatement i && i.elseBranch() != null) {
                Set<String> thenDecls = new HashSet<>();
                Set<String> elseDecls = new HashSet<>();
                collectBranchDecls(i.thenBranch(), thenDecls);
                collectBranchDecls(i.elseBranch(), elseDecls);
                Set<String> hoisted = new HashSet<>(thenDecls);
                hoisted.retainAll(elseDecls);
                if (hoisted.isEmpty()) {
                    out.add(c);
                    continue;
                }
                List<Statement> pre = new ArrayList<>();
                for (String name : hoisted) {
                    JavaType t = branchDeclType(i.thenBranch(), name);
                    if (t == null) {
                        t = branchDeclType(i.elseBranch(), name);
                    }
                    pre.add(new VariableDeclaration(t != null ? t : JavaType.INT, name, null));
                }
                pre.add(new IfStatement(i.condition(),
                        hoistBranchDecls(i.thenBranch(), hoisted),
                        hoistBranchDecls(i.elseBranch(), hoisted)));
                out.addAll(pre);
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /** 收集分支体中顶层声明的变量名(不递归进入嵌套控制流). */
    private void collectBranchDecls(Statement s, Set<String> out) {
        if (s instanceof VariableDeclaration vd) {
            out.add(vd.name());
        } else if (s instanceof BlockStatement bs) {
            for (Statement c : bs.statements()) {
                if (c instanceof VariableDeclaration vd) {
                    out.add(vd.name());
                }
            }
        }
    }

    /** 查找分支体中某变量的声明类型(用于提升后的 {@code Type y;} 前置声明). */
    private JavaType branchDeclType(Statement s, String name) {
        if (s instanceof VariableDeclaration vd && vd.name().equals(name)) {
            return vd.type();
        }
        if (s instanceof BlockStatement bs) {
            for (Statement c : bs.statements()) {
                if (c instanceof VariableDeclaration vd && vd.name().equals(name)) {
                    return vd.type();
                }
            }
        }
        return null;
    }

    /** 将分支体中已提升变量的声明转为赋值语句(无初始化器则删除该声明). */
    private Statement hoistBranchDecls(Statement s, Set<String> hoisted) {
        if (s instanceof VariableDeclaration vd && hoisted.contains(vd.name())) {
            if (vd.initializer() != null) {
                return new ExpressionStatement(
                        new AssignExpr(new VarExpr(vd.name()), vd.initializer()));
            }
            return null; // 无初始化器,声明已提升到 if 之前,此处删除
        }
        if (s instanceof BlockStatement bs) {
            List<Statement> out = new ArrayList<>();
            for (Statement c : bs.statements()) {
                Statement transformed = hoistBranchDecls(c, hoisted);
                if (transformed != null) {
                    out.add(transformed);
                }
            }
            return new BlockStatement(out);
        }
        return s;
    }

    /**
     * 从 instanceof 模式中收集模式变量名.
     * 模式形如 VarExpr("RecordDemo(String name, int age)")——
     * 解析括号内的 "Type name" 令牌,提取变量名.
     *
     * @param e   条件表达式
     * @param out 输出集合,收集到的模式变量名将添加至此
     */
    private void collectPatternVars(Expression e, Set<String> out) {
        if (e instanceof BinExpr b
                && b.operator() == com.bingbaihanji.bdec.ast.expr.BinaryOperator.INSTANCEOF
                && b.right() instanceof VarExpr tv) {
            String pattern = tv.name();
            int open = pattern.indexOf('(');
            if (open >= 0) {
                // 记录解构模式:RecordDemo(String name, int age)
                int close = pattern.lastIndexOf(')');
                if (close > open) {
                    String[] parts = pattern.substring(open + 1, close).split(",");
                    for (String p : parts) {
                        String[] tokens = p.trim().split("\\s+");
                        if (tokens.length >= 2) {
                            out.add(tokens[tokens.length - 1]);
                        }
                    }
                }
            } else {
                // 简单类型模式:Type name(如 "String s")
                String[] tokens = pattern.trim().split("\\s+");
                if (tokens.length >= 2) {
                    out.add(tokens[tokens.length - 1]);
                }
            }
        } else if (e instanceof UnExpr u) {
            collectPatternVars(u.operand(), out);
        }
    }

    /**
     * 递归收集语句中引用的所有变量名.
     *
     * @param s   待遍历的语句
     * @param out 输出集合,收集到的变量名将添加至此
     */
    private void collectVarNames(Statement s, Set<String> out) {
        if (s instanceof ExpressionStatement es) {
            collectVarNamesInExpr(es.expression(), out);
        } else if (s instanceof ReturnStatement rs && rs.value() != null) {
            collectVarNamesInExpr(rs.value(), out);
        } else if (s instanceof ThrowStatement ts && ts.expression() != null) {
            collectVarNamesInExpr(ts.expression(), out);
        } else if (s instanceof IfStatement i) {
            // 仅收集条件中的变量.分支体有独立作用域,
            // 由递归 fix() 处理——此处收集会把分支体内引用的
            // 变量(如记录模式的 name/age)错误地提升到外层声明.
            collectVarNamesInExpr(i.condition(), out);
        } else if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            collectVarNamesInExpr(vd.initializer(), out);
        }
    }

    /**
     * 递归收集表达式中引用的所有变量名.
     *
     * @param e   待遍历的表达式
     * @param out 输出集合
     */
    private void collectVarNamesInExpr(Expression e, Set<String> out) {
        switch (e) {
            case null -> {
                return;
            }
            case VarExpr v -> out.add(v.name());
            case BinExpr b -> {
                collectVarNamesInExpr(b.left(), out);
                collectVarNamesInExpr(b.right(), out);
            }
            case UnExpr u -> collectVarNamesInExpr(u.operand(), out);
            case InvocationExpr inv -> {
                if (inv.target() != null) {
                    collectVarNamesInExpr(inv.target(), out);
                }
                for (Expression a : inv.arguments()) {
                    collectVarNamesInExpr(a, out);
                }
            }
            case FieldAccessExpr fa when fa.target() != null -> {
                // 静态字段访问的目标是类型名(如 System.out,
                // java.util.concurrent.TimeUnit.SECONDS,int.class 的 "int"),
                // 不是局部变量——仅收集实例字段访问的真实变量目标.
                if (!(fa.target() instanceof VarExpr tv && SourceCleanupSupport.isTypeName(tv.name()))) {
                    collectVarNamesInExpr(fa.target(), out);
                }
            }
            case AssignExpr a -> {
                collectVarNamesInExpr(a.target(), out);
                collectVarNamesInExpr(a.value(), out);
            }
            default -> {
            }
        }
    }

    /**
     * 判断名称是否为 Java 内置关键字/字面量.
     *
     * @param name 待检查的名称
     * @return 若为内置关键字或字面量返回 {@code true}
     */
    private boolean isBuiltin(String name) {
        return "null".equals(name) || "this".equals(name)
                || "true".equals(name) || "false".equals(name)
                || "super".equals(name);
    }

    /**
     * 根据 Java 类型返回对应的默认值表达式.
     *
     * @param t Java 类型
     * @return 默认值表达式(整型为 0,浮点为 0.0,布尔为 false,对象为 null)
     */
    private Expression defaultVal(JavaType t) {
        if (t == null) {
            return new VarExpr("null");
        }
        return switch (t.kind()) {
            case INT, SHORT, BYTE, CHAR -> new LitExpr(0, JavaType.INT);
            case LONG -> new LitExpr(0L, JavaType.LONG);
            case FLOAT -> new LitExpr(0.0f, JavaType.FLOAT);
            case DOUBLE -> new LitExpr(0.0d, JavaType.DOUBLE);
            case BOOLEAN -> new LitExpr(false, JavaType.BOOLEAN);
            default -> new VarExpr("null");
        };
    }
}
