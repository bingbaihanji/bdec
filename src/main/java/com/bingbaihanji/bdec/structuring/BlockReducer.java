package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.type.TypeResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基本块归约器.
 *
 * <p>将结构化的控制流图(CFG)转换为 AST 语句,将 {@link IrInstruction} 对象
 * 翻译为正确的 AST 表达式/语句节点.
 *
 * <p>核心设计:只有产生副作用的指令(STORE,RETURN,THROW,INVOKE 等)才会
 * 成为语句.中间值指令(LOAD,CONST,BINARY,CAST 等)通过追踪
 * {@link InstructionRef} 链来解析为表达式树.
 */
public final class BlockReducer {

    /** 常见函数式接口(单一抽象方法)的方法名集合 */
    private static final Set<String> SAM_METHOD_NAMES = Set.of(
            "run", "call", "get", "apply", "accept", "test",
            "compare", "compareTo", "getAsBoolean", "getAsInt",
            "getAsLong", "getAsDouble", "thenApply", "thenAccept",
            "thenRun", "thenCompose", "thenCombine", "supply",
            "applyAsInt", "applyAsLong", "applyAsDouble",
            "andThen", "compose", "negate", "or", "and");

    /** 当前方法是否为实例方法 */
    private final boolean isInstanceMethod;

    /** 已声明变量名的作用域栈.进入/离开分支体时压入/弹出,
     *  确保每个分支拥有独立的临时变量声明. */
    private final Deque<Set<String>> declaredVarNameStack = new ArrayDeque<>();

    /** NEW+INIT 合并的临时状态(CondenseConstruction 模式) */
    private Map<Integer, List<IrInstruction>> currentNewToInit = Map.of();

    /** 已合并到 NEW 中,需要跳过的 INIT 指令 ID 集合 */
    private Set<Integer> currentInitToSkip = Set.of();

    /** STORE→Variable→LOAD 链的临时内联状态 */
    private Map<Variable, Value> currentVarStoreSource = Map.of();

    /** 已被内联,需要跳过的 STORE 指令 ID 集合 */
    private Set<Integer> currentStoresToSkip = Set.of();

    /** 分支上下文中的基本块 ID 集合,用于 PHI 解析.
     *  在翻译分支体时记录哪些块属于当前分支,以便 PHI 节点选择正确的操作数. */
    private Set<Integer> currentBranchBlocks = null;

    /** 当前方法是否返回 boolean 类型(缓存值) */
    private boolean currentMethodReturnsBoolean = false;

    /** 当前正在处理的 LinearIr(在 reduce() 开始时设置,用于字段/局部变量名冲突检测) */
    private LinearIr currentIr = null;

    /** 跨组全局变量使用计数,防止 per-group 内联仅因组内 load 计数为 1
     *  而内联变量,但该变量在其他组中仍有引用. */
    private Map<Variable, Integer> globalVarUseCount = Map.of();

    /** INDY 指令翻译器,将 invokedynamic 模式转换为 LambdaExpr */
    private final IndyTranslator indyTranslator;

    public BlockReducer() {this(true);}

    public BlockReducer(boolean isInstanceMethod) {
        this.isInstanceMethod = isInstanceMethod;
        this.indyTranslator = new IndyTranslator(
                this::getIndyAnnotation, this::valueToExpr);
    }

    /** 判断指令是否产生副作用,从而应成为一条语句 */
    private static boolean isStatementRoot(IrInstruction insn) {
        return switch (insn.opcode()) {
            case STORE, RETURN, THROW, FIELD_STORE, ARRAY_STORE -> true;
            case INVOKE -> insn.resultType().kind() == TypeKind.VOID
                    || insn.resultType().kind() == null; // void 类型调用
            case INC -> true; // IINC 总以语句形式出现
            case MONITOR_ENTER, MONITOR_EXIT -> true;
            default -> false;
        };
    }

    /** 判断表达式是否可忽略——仅裸变量或临时引用 */
    private static boolean isIgnorableExpr(Expression e) {
        if (e instanceof VarExpr v) {
            String name = v.name();
            return name.startsWith("var") || name.startsWith("tmp") || name.startsWith("?")
                    || "this".equals(name);
        }
        // 跳过独立的字面量表达式——字符 串,数字,布尔值本身不是合法的独立语句
        if (e instanceof LitExpr) {
            return true;
        }
        // 独立的字段访问(例如 GETSTATIC 用于方法引用时的 System.out)不是合法的 Java 语句
        if (e instanceof FieldAccessExpr) {
            return true;
        }
        return false;
    }

    /** 判断表达式是否产生 void 类型(如 void 方法调用) */
    private static boolean isVoidExpr(Expression e) {
        return e instanceof InvocationExpr inv
                && inv.returnType() != null
                && inv.returnType().kind() == TypeKind.VOID;
    }

    /** 判断表达式是否为(复合)赋值表达式.
     *  赋值表达式不能安全地提升为 return 表达式——其类型可能与方法返回类型不匹配. */
    private static boolean isAssignExpr(Expression e) {
        return e instanceof com.bingbaihanji.bdec.ast.expr.AssignExpr
                || e instanceof com.bingbaihanji.bdec.ast.expr.UnExpr
                && (((com.bingbaihanji.bdec.ast.expr.UnExpr) e).operator()
                == com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC
                || ((com.bingbaihanji.bdec.ast.expr.UnExpr) e).operator()
                == com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC);
    }

    /** 判断语句块是否为空或仅包含空块 */
    private static boolean isEmptyBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            return bs.statements().isEmpty()
                    || bs.statements().stream().allMatch(BlockReducer::isEmptyBlock);
        }
        return false;
    }

    // ── 块分组工具方法 ────────────────────────────────────────────────

    /** 判断表达式是否为指定值的布尔字面量 */
    private static boolean isBooleanLit(Expression e, boolean expected) {
        if (e instanceof LitExpr lit) {
            Object v = lit.value();
            return v instanceof Boolean b && b == expected;
        }
        return false;
    }

    /** 检测自增/自减模式:x = x + 1 → x++, x = x - 1 → x-- */
    private static UnaryOperator detectIncrement(BinExpr bin) {
        boolean isOne = bin.right() instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                && lr.value() instanceof Integer i && i == 1;
        if (!isOne) {
            return null;
        }
        if (bin.operator() == BinaryOperator.ADD) {
            return com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC;
        }
        if (bin.operator() == BinaryOperator.SUB) {
            return com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC;
        }
        return null;
    }

    /** 检查某条语句是否与候选列表中的任一语句在表达式结构上匹配 */
    private static boolean matchesAny(Statement s, List<Statement> candidates) {
        if (s instanceof ExpressionStatement es) {
            for (Statement c : candidates) {
                if (c instanceof ExpressionStatement ce
                        && expressionsEquivalent(es.expression(), ce.expression())) {
                    return true;
                }
            }
        }
        if (s instanceof ReturnStatement rs && rs.value() != null) {
            for (Statement c : candidates) {
                if (c instanceof ReturnStatement rc && rc.value() != null
                        && expressionsEquivalent(rs.value(), rc.value())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 两个表达式树的结构化比较 */
    private static boolean expressionsEquivalent(Expression a, Expression b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }

        switch (a) {
            case InvocationExpr ia when b instanceof InvocationExpr ib -> {
                if (!ia.methodName().equals(ib.methodName())) {
                    return false;
                }
                if (ia.arguments().size() != ib.arguments().size()) {
                    return false;
                }
                for (int i = 0; i < ia.arguments().size(); i++) {
                    if (!expressionsEquivalent(ia.arguments().get(i), ib.arguments().get(i))) {
                        return false;
                    }
                }
                return expressionsEquivalent(ia.target(), ib.target());
            }
            case LitExpr la when b instanceof LitExpr lb -> {
                Object va = la.value(), vb = lb.value();
                return Objects.equals(va, vb);
            }
            case VarExpr va when b instanceof VarExpr vb -> {
                return va.name().equals(vb.name());
            }
            case FieldAccessExpr fa when b instanceof FieldAccessExpr fb -> {
                return fa.fieldName().equals(fb.fieldName())
                        && expressionsEquivalent(fa.target(), fb.target());
            }
            default -> {
            }
        }
        return false;
    }

    /** 递归收集所有语句,展开嵌套的 BlockStatement */
    private static List<Statement> collectStatements(Statement s) {
        List<Statement> result = new ArrayList<>();
        if (s instanceof BlockStatement bs) {
            for (Statement child : bs.statements()) {
                result.addAll(collectStatements(child));
            }
        } else {
            result.add(s);
        }
        return result;
    }

    /** 去除内部类/局部类/匿名类构造函数的隐式外围实例(this)参数.
     *  在 Java 源码中,{@code new InnerClass()} 不需要显式传递 this,
     *  而字节码中内部类构造函数会包含外围实例作为第一个参数. */
    private static List<Expression> stripEnclosingThis(JavaType targetType, List<Expression> args) {
        if (args.isEmpty()) {
            return args;
        }
        // 仅对内部类/局部类/匿名类(名称含 $)进行处理
        String internal = targetType.internalName();
        if (internal == null) {
            return args;
        }
        int lastSlash = internal.lastIndexOf('/');
        String simple = lastSlash >= 0 ? internal.substring(lastSlash + 1) : internal;
        if (!simple.contains("$")) {
            return args; // 非内部类,不处理
        }
        // 第一个参数若是 this 引用,则是隐含的外围实例,予以去除
        Expression first = args.getFirst();
        if (first instanceof VarExpr v && "this".equals(v.name())) {
            List<Expression> filtered = new ArrayList<>(args);
            filtered.removeFirst();
            return filtered;
        }
        return args;
    }

    /** 检查指令列表中是否包含 NEW 指令(对象创建).
     *  用于区分 catch 子句(创建新异常)与 finally 块(重新抛出原始异常). */
    private static boolean containsNewInstruction(List<IrInstruction> insns) {
        for (IrInstruction i : insns) {
            if (i.opcode() == IrOpcode.NEW) {
                return true;
            }
        }
        return false;
    }

    /** 检查处理器基本块中是否包含 NEW 指令(用于区分 catch 与 finally). */
    private static boolean handlerBlockContainsNew(BasicBlock handlerBlock, LinearIr ir) {
        if (handlerBlock == null || ir == null) {
            return false;
        }
        for (IrInstruction i : ir.instructionsOf(handlerBlock)) {
            if (i.opcode() == IrOpcode.NEW) {
                return true;
            }
        }
        return false;
    }

    /** 判断值是否简单到可以内联(常量或基本表达式) */
    private static boolean isSimpleValue(Value v) {
        if (v instanceof ConstantValue) {
            return true;
        }
        if (v instanceof InstructionRef ref) {
            IrOpcode op = ref.instruction().opcode();
            return op == IrOpcode.CONST || op == IrOpcode.LOAD || op == IrOpcode.CAST
                    || op == IrOpcode.FIELD_LOAD || op == IrOpcode.ARRAY_LENGTH
                    || op == IrOpcode.INSTANCE_OF;
        }
        return false;
    }

    /** 检查语句树中是否包含 ReturnStatement */
    private static boolean hasReturnStmt(Statement s) {
        if (s instanceof ReturnStatement) {
            return true;
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream().anyMatch(BlockReducer::hasReturnStmt);
        }
        // 递归检查可能包含 return 的复合语句
        if (s instanceof IfStatement i) {
            return hasReturnStmt(i.thenBranch())
                    || (i.elseBranch() != null && hasReturnStmt(i.elseBranch()));
        }
        if (s instanceof LoopStatement l) {
            return hasReturnStmt(l.body());
        }
        if (s instanceof TryStatement t) {
            boolean inTry = hasReturnStmt(t.tryBody());
            boolean inCatch = t.catchClauses().stream()
                    .anyMatch(cc -> hasReturnStmt(cc.body()));
            boolean inFinally = t.finallyBody() != null
                    && hasReturnStmt(t.finallyBody());
            return inTry || inCatch || inFinally;
        }
        return false;
    }

    /** 从已包含 ReturnStatement 的分支体中剥离孤立的 ExpressionStatement.
     *  这些语句通常是合并点处的块排序噪声,无实际意义. */
    private static Statement stripOrphanExprs(Statement s) {
        if (s instanceof BlockStatement bs) {
            boolean hasAnyReturn = bs.statements().stream().anyMatch(BlockReducer::hasReturnStmt);
            if (!hasAnyReturn) {
                return s;
            }
            List<Statement> filtered = new ArrayList<>();
            for (Statement child : bs.statements()) {
                // 仅剥离无意义的 ExpressionStatement(变量引用,临时变量引用),
                // 不剥离具有真正副作用的表达式(如字段赋值)
                if (child instanceof ExpressionStatement es
                        && isIgnorableExpr(es.expression())) {
                    continue; // 剥离孤立的 CONST/temp
                }
                if (child instanceof BlockStatement) {
                    Statement stripped = stripOrphanExprs(child);
                    if (!isEmptyBlock(stripped)) {
                        filtered.add(stripped);
                    }
                } else {
                    filtered.add(child);
                }
            }
            if (filtered.isEmpty()) {
                return new BlockStatement(List.of());
            }
            if (filtered.size() == 1) {
                return filtered.getFirst();
            }
            return new BlockStatement(filtered);
        }
        return s;
    }

    /** 将分支体中的 ExpressionStatement 包装为 ReturnStatement
     * (处理没有自身 RETURN 的分支中的孤立的 CONST).
     *  跳过 void 表达式(例如孤立的 lock.unlock() 调用)以避免
     *  "void cannot be converted to int" 编译错误. */
    private static Statement wrapAsReturn(Statement s, boolean isBoolRet, boolean isVoidRet) {
        if (s instanceof BlockStatement bs) {
            if (hasReturnStmt(s)) {
                return s; // 已有 RETURN
            }
            List<Statement> result = new ArrayList<>();
            boolean addedReturn = false;
            for (Statement child : bs.statements()) {
                if (child instanceof ExpressionStatement es) {
                    Expression e = es.expression();
                    // 保留 void 方法调用、赋值、非 void 方法调用(其结果可能
                    // 被后续 STORE 消费)和字段访问原样,不包装为 return.
                    // 只有简单值(常量、变量、转换)才包装为 return.
                    if (isVoidExpr(e) || isAssignExpr(e)
                            || e instanceof InvocationExpr
                            || e instanceof FieldAccessExpr) {
                        result.add(child); // 保留原样
                    } else {
                        result.add(new ReturnStatement(boolLiteral(e, isBoolRet)));
                        addedReturn = true;
                    }
                } else if (child instanceof BlockStatement inner) {
                    Statement wrapped = wrapAsReturn(inner, isBoolRet, isVoidRet);
                    result.add(wrapped);
                    if (hasReturnStmt(wrapped)) {
                        addedReturn = true;
                    }
                } else {
                    result.add(child);
                    if (child instanceof ReturnStatement) {
                        addedReturn = true;
                    }
                }
            }
            // 如果未添加任何 return,追加一个合成 return 以确保方法可编译
            // void 方法使用 return; (无值),boolean 方法使用 return false;,其他使用 return null;
            if (!addedReturn) {
                if (isVoidRet) {
                    result.add(new ReturnStatement(null));
                } else {
                    result.add(new ReturnStatement(isBoolRet
                            ? new com.bingbaihanji.bdec.ast.expr.LitExpr(false, JavaType.BOOLEAN)
                            : new com.bingbaihanji.bdec.ast.expr.LitExpr(null,
                                    JavaType.classType("java/lang/Object"))));
                }
            }
            if (result.isEmpty()) {
                return new BlockStatement(List.of());
            }
            if (result.size() == 1) {
                return result.getFirst();
            }
            return new BlockStatement(result);
        }
        if (s instanceof ExpressionStatement es) {
            if (isVoidExpr(es.expression()) || isAssignExpr(es.expression())) {
                // 对 void 方法使用 return;(无值),否则追加合成 null/0/false return
                if (isVoidRet) {
                    return new BlockStatement(List.of(s, new ReturnStatement(null)));
                }
                return new BlockStatement(List.of(
                        s,
                        new ReturnStatement(isBoolRet
                                ? new com.bingbaihanji.bdec.ast.expr.LitExpr(false, JavaType.BOOLEAN)
                                : new com.bingbaihanji.bdec.ast.expr.LitExpr(null, JavaType.classType("java/lang/Object")))));
            }
            return new ReturnStatement(boolLiteral(es.expression(), isBoolRet));
        }
        return s;
    }

    /** 对 boolean 返回方法,将整数字面量转为布尔值 */
    private static Expression boolLiteral(Expression e, boolean isBoolRet) {
        if (isBoolRet && e instanceof LitExpr lit && lit.value() instanceof Integer i) {
            return new LitExpr(i != 0, JavaType.BOOLEAN);
        }
        return e;
    }

    /** 检查方法名是否为已知的 SAM(单一抽象方法)名称 */
    private static boolean isSamMethodName(String name) {
        return name != null && SAM_METHOD_NAMES.contains(name);
    }

    /** 检查类型是否类似函数式接口(java.util.function.* 或类似) */
    private static boolean isFunctionalInterfaceLike(JavaType type) {
        if (type == null) {
            return false;
        }
        String desc = type.descriptor();
        if (desc == null) {
            return false;
        }
        // java.util.function 包下的函数式接口
        return desc.contains("java/util/function/")
                || desc.contains("java/util/Comparator")
                || desc.contains("java/lang/Runnable")
                || desc.contains("java/util/concurrent/Callable");
    }

    // ── Group → Statement 翻译 ──────────────────────────────

    /** 从函数式接口类型中提取简短显示名称 */
    private static String functionalInterfaceShortName(JavaType type) {
        if (type == null) {
            return null;
        }
        String desc = type.descriptor();
        if (desc == null) {
            return null;
        }
        // 从 "Ljava/util/function/Function;" 提取 "Function"
        if (desc.startsWith("L") && desc.endsWith(";")) {
            String internal = desc.substring(1, desc.length() - 1);
            int slash = internal.lastIndexOf('/');
            return slash >= 0 ? internal.substring(slash + 1) : internal;
        }
        return desc;
    }

    /** 将完全限定内部类名简化为短名称.
     *  "java/lang/String" → "String" */
    private static String simplifyClassName(String internalName) {
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

    /** 检查类型是否为 java.lang.Class 类型 */
    private static boolean isClassType(com.bingbaihanji.bdec.type.JavaType type) {
        return type != null && type.kind() == TypeKind.CLASS
                && "java/lang/Class".equals(type.internalName());
    }

    // ── IR → Statement ─────────────────────────────────────────────

    /** 判断 Value 是否表示布尔值(变量,方法返回值等).
     *  用于生成正确的 {@code if(flag)} / {@code if(!flag)} 语句,
     *  而不是 {@code if(flag != 0)}——后者对布尔表达式会产生类型不匹配错误. */
    private static boolean isBooleanValue(Value v) {
        if (v instanceof Variable var) {
            return var.type().kind() == TypeKind.BOOLEAN;
        }
        if (v instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            // 检查定义指令是否产生布尔类型结果
            if (def.resultType() != null
                    && def.resultType().kind() == TypeKind.BOOLEAN) {
                return true;
            }
            // 返回布尔值的 INVOKE 指令
            if (def.opcode() == IrOpcode.INVOKE && def.resultType() != null
                    && def.resultType().kind() == TypeKind.BOOLEAN) {
                return true;
            }
            // CONDITION,COMPARE,INSTANCE_OF 产生布尔值
            // INSTANCE_OF 在 JVM 字节码层面产生 int(0/1),但在 Java 源码中
            // 总是用作布尔条件,因此应简化 CONDITION 中的 "==0"/"!=0" 比较
            return def.opcode() == IrOpcode.CONDITION
                    || def.opcode() == IrOpcode.COMPARE
                    || def.opcode() == IrOpcode.INSTANCE_OF;
        }
        return false;
    }

    // ── IR → Expression ────────────────────────────────────────────

    /**
     * 后处理:根据 CFG 异常范围将语句组包装为 try-catch 结构.
     * 在 if/else/loop 结构化之后运行,以确保嵌套控制结构得以保留.
     *
     * <p>核心思路:追踪哪些原始基本块属于每个 try 范围,
     * 将最终语句重新组装回对应的组中,找出所有基本块均位于 try 范围内的组,
     * 并仅包装这些组.
     */
    private BlockStatement wrapTryCatchBlocks(BlockStatement root,
                                              List<BlockGroup> groups,
                                              Map<BasicBlock, TryCatchInfo> tryCatchAnns,
                                              LinearIr ir) {
        if (tryCatchAnns.isEmpty()) {
            return root;
        }

        List<Statement> stmts = new ArrayList<>(root.statements());

        // 对每个 try-catch 注解,找出所有基本块均在 try 范围内的连续组
        for (var entry : tryCatchAnns.entrySet()) {
            TryCatchInfo tci = entry.getValue();

            // 找出仅包含 try 范围块的组
            int firstTryGroup = -1;
            int lastTryGroup = -1;
            for (int i = 0; i < groups.size(); i++) {
                boolean allInTry = true;
                boolean anyInTry = false;
                for (BasicBlock b : groups.get(i).blocks()) {
                    if (tci.tryBlocks().contains(b)) {
                        anyInTry = true;
                    } else {
                        allInTry = false;
                    }
                }
                if (anyInTry && allInTry) {
                    if (firstTryGroup < 0) {
                        firstTryGroup = i;
                    }
                    lastTryGroup = i;
                }
            }

            if (firstTryGroup < 0 || firstTryGroup >= stmts.size()) {
                continue;
            }

            // 对于 finally 模式(catch-all 处理器),将 try 体扩展到
            // 包含 try 范围之后的正常退出块.
            // 正常退出块(位于 endPc 处)包含:
            //   [finally 体副本] [返回值]
            // 我们希望生成:try { return value; } finally { ... }
            // 正常退出路径中重复的 finally 代码将被剥离.
            // 例外:若处理器块包含 NEW 指令(如 MatchException 处理器),
            // 则不是 finally 模式,而是普通 catch 子句.
            boolean isFinally = (tci.catchType() == null
                    || "java/lang/Throwable".equals(tci.catchType()))
                    && !handlerBlockContainsNew(tci.handlerBlock(), ir);

            // 收集正常退出组(位于 try 范围之后,处理器之前)
            int normalExitEnd = lastTryGroup;
            if (isFinally) {
                // 找出包含不属于 tryBlocks 且不是处理器,但在 lastTryGroup 和处理器组之间的块
                for (int i = lastTryGroup + 1; i < groups.size(); i++) {
                    BlockGroup g = groups.get(i);
                    boolean hasHandler = false;
                    boolean hasTry = false;
                    for (BasicBlock b : g.blocks()) {
                        if (b == tci.handlerBlock()) {
                            hasHandler = true;
                        }
                        if (tci.tryBlocks().contains(b)) {
                            hasTry = true;
                        }
                    }
                    if (hasHandler || hasTry) {
                        break; // 遇到处理器或下一个 try 范围即停止
                    }
                    normalExitEnd = i; // 包含此组
                }
            }

            // 构建 try 体:从 firstTryGroup 到 normalExitEnd 的组
            List<Statement> tryBodyStmts = new ArrayList<>();
            for (int i = firstTryGroup; i <= normalExitEnd && i < stmts.size(); i++) {
                tryBodyStmts.add(stmts.get(i));
            }

            if (!tryBodyStmts.isEmpty()) {
                Statement tryBody = tryBodyStmts.size() == 1
                        ? tryBodyStmts.get(0)
                        : new BlockStatement(tryBodyStmts);

                // 跳过 synchronized 块的 try-catch 包装——
                // 异常处理器是 JVM 伪影(monitorexit 重试),
                // 而非真正的 Java 源码 catch/finally.
                if (containsSynchronizedStatement(tryBody)) {
                    continue;
                }

                // 跳过 MatchException 模式匹配处理器的 try-catch 包装.
                // 记录模式/类型模式生成的 MatchException 处理器
                // (NEW MatchException + THROW)会导致 try 体被分割,
                // INVOKE 与 STORE 指令被拆分到不同基本块中产生死代码.
                // 直接输出 try 体以保持指令对的完整性.
                if (isMatchExceptionHandler(tci, ir)) {
                    continue;
                }

                // 检测 synchronized 块模式:try 体包含 MONITOR_ENTER,
                // 处理器执行 MONITOR_EXIT + THROW.
                // 直接生成 SynchronizedStatement 而非 try-finally.
                if (isSynchronizedHandler(tci, ir)) {
                    String monObj = extractMonitorObject(tci, ir);
                    SynchronizedStatement syncStmt = new SynchronizedStatement(
                            new VarExpr(monObj), tryBody);
                    // 从方法体中剥离 synchronized 前导代码(DUP/ASTORE)
                    syncStmt = stripSyncPreamble(syncStmt);
                    stmts.set(firstTryGroup, syncStmt);
                } else {
                    stmts.set(firstTryGroup, buildTryCatch(tci, tryBody, ir));
                }
                // 移除已被吸收的组
                for (int i = normalExitEnd; i > firstTryGroup; i--) {
                    if (i < stmts.size()) {
                        stmts.remove(i);
                    }
                }
            }
        }

        return new BlockStatement(stmts);
    }

    /**
     * 将控制流图归约为 AST 语句块.
     *
     * @param graph       控制流图
     * @param ir          线性 IR
     * @param loopAnns    循环注解映射
     * @param ifAnns      if 注解映射
     * @param switchAnns  switch 注解映射
     * @param tryCatchAnns try-catch 注解映射
     * @return 归约后的 BlockStatement
     */
    public BlockStatement reduce(ControlFlowGraph graph, LinearIr ir,
                                 Map<BasicBlock, LoopInfo> loopAnns,
                                 Map<BasicBlock, IfInfo> ifAnns,
                                 Map<BasicBlock, SwitchInfo> switchAnns,
                                 Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        // 按支配树先序遍历排序基本块(而非起始偏移量排序),
        // 确保构造函数体按控制流顺序出现.
        List<BasicBlock> sorted = dominatorTreeOrder(graph);
        if (sorted.isEmpty()) {
            return new BlockStatement(List.of());
        }

        // 缓存方法返回类型信息
        currentMethodReturnsBoolean = ir.method().returnType() != null
                && ir.method().returnType().kind() == TypeKind.BOOLEAN;
        this.currentIr = ir;
        this.declaredVarNameStack.clear();
        this.declaredVarNameStack.push(new HashSet<>()); // 顶层作用域

        // 预先计算后支配树,用于在 BranchAnalyzer 注解缺失时
        // 作为 if-header 检测的回退方案,以提供正确的合并点.
        PostDominatorTree postDom = PostDominatorTree.compute(graph);

        List<BlockGroup> groups = groupAdjacentBlocks(sorted, graph, loopAnns);
        Set<BlockGroup> consumed = new HashSet<>();
        // 收集处理器块,以便跳过它们对应的组(这些组将被
        // wrapTryCatchBlocks 吸收到 try-finally 中).
        Set<BasicBlock> handlerBlocks = new HashSet<>();
        for (TryCatchInfo tci : tryCatchAnns.values()) {
            BasicBlock hb = tci.handlerBlock();
            handlerBlocks.add(hb);
            // 沿 fallthrough 链追踪以收集所有处理器片段
            Set<BasicBlock> visited = new HashSet<>();
            Deque<BasicBlock> queue = new ArrayDeque<>();
            queue.add(hb);
            while (!queue.isEmpty()) {
                BasicBlock curr = queue.poll();
                if (!visited.add(curr)) {
                    continue;
                }
                handlerBlocks.add(curr);
                for (var edge : graph.outgoingOf(curr)) {
                    if (edge.kind() != EdgeKind.EXCEPTION
                            && edge.target() != graph.exitBlock()) {
                        queue.add(edge.target());
                    }
                }
            }
        }
        List<Statement> statements = new ArrayList<>();

        // ── 全局变量内联预遍历 ─────────────────────────
        // 扫描所有组以找出恰好被使用一次的变量的 STORE→Variable→LOAD 链.
        // 该操作跨组边界工作(对 try-finally 模式至关重要:STORE 位于 try 体组,
        // 而 LOAD+RETURN 位于正常退出组).
        buildGlobalVarInlineMap(groups, ir);

        // 全局预遍历:跨组合并 NEW + INVOKE <init> 对(CondenseConstruction).
        // 某些情况下 NEW 指令位于一个 BlockGroup 而对应的 <init> 调用
        // 位于另一个 BlockGroup 中(例如 record 构造, sealed 类构造等).
        buildGlobalNewInitMergeMap(groups, ir);

        for (int gi = 0; gi < groups.size(); gi++) {
            BlockGroup group = groups.get(gi);
            if (consumed.contains(group)) {
                continue;
            }
            consumed.add(group);

            // 查找匹配的注解——检查组内所有块而非仅 group.first(),
            // 因为 CFG 折叠可能已将注解头与前面的块合并.
            IfInfo ifInfo = findIfAnnotation(group, ifAnns);
            LoopInfo loopInfo = findLoopAnnotation(group, loopAnns);
            TryCatchInfo tryCatchInfo = findTryAnnotation(group, tryCatchAnns);
            SwitchInfo switchInfo = findSwitchAnnotation(group, switchAnns);

            // 回退方案:若无 IfInfo 注解,则尝试直接从 CFG 结构检测 if-header
            //(具有 2 个后继的条件块).使用后支配树计算正确的合并点.
            if (ifInfo == null) {
                ifInfo = detectIfHeader(group, graph, ir, postDom);
            }

            Statement s;

            // if-else:构建包含 then 和 else 体的完整 IfStatement
            if (ifInfo != null) {
                // 优先从组内提取条件;回退方案依次尝试 IfInfo 头部块及全部组
                Expression rawCond = extractCondition(group, ir);
                if (rawCond == null && ifInfo != null) {
                    rawCond = extractConditionFromHeader(ifInfo.header(), ir);
                }
                if (rawCond == null && ifInfo != null) {
                    rawCond = extractConditionFromAllGroups(groups, ir);
                }
                Expression cond = simplifyCondition(rawCond);

                // 翻译头部组中的非条件语句.
                // 当包含 if-header 的组中也包含在条件之前执行的代码时
                //(例如在最终三元条件之前的位操作),这些代码必须出现在 IfStatement 之前.
                List<Statement> preIfStmts = translateHeaderNonCondition(group, ir);

                // 翻译 then 体:找出包含 then 块的组
                Statement thenBody = translateBranchBody(ifInfo.thenBlocks(), groups, ir, consumed, graph, postDom);

                // 翻译 else 体:找出包含 else 块的组
                Statement elseBody = null;
                if (!ifInfo.elseBlocks().isEmpty()) {
                    elseBody = translateBranchBody(ifInfo.elseBlocks(), groups, ir, consumed, graph, postDom);
                }

                // 消除空 else 块——不输出 "else { }"
                if (isEmptyBlock(elseBody)) {
                    elseBody = null;
                }

                // 对 if-else 模式的分支体进行后处理:两个分支都在共同的
                // RETURN 块处合并计算值.
                boolean thenHasReturn = hasReturnStmt(thenBody);
                boolean elseHasReturn = hasReturnStmt(elseBody);
                boolean isBoolRet = ir.method().returnType() != null
                        && ir.method().returnType().kind() == TypeKind.BOOLEAN;
                boolean isVoidRet = ir.method().returnType() != null
                        && ir.method().returnType().kind() == TypeKind.VOID;

                if (thenHasReturn != elseHasReturn) {
                    if (thenHasReturn) {
                        thenBody = stripOrphanExprs(thenBody);
                        if (elseBody != null) {
                            elseBody = wrapAsReturn(elseBody, isBoolRet, isVoidRet);
                        }
                    } else {
                        if (thenBody != null) {
                            thenBody = wrapAsReturn(thenBody, isBoolRet, isVoidRet);
                        }
                        elseBody = stripOrphanExprs(elseBody);
                    }
                }

                // 当 then 体来自 false 分支(trueTarget==follow)时,
                // CONDITION 需要取反以产生正确的 Java 语义.
                // 例如:ifeq→CONDITION 翻译为 !(值),但 then 体是 false 分支
                // (值!=0 时的代码),因此需要再次取反以还原为原始 boolean 值.
                if (ifInfo.negateCondition() && cond != null) {
                    cond = new UnExpr(UnaryOperator.NOT, cond);
                    cond = simplifyCondition(cond);
                }

                s = new IfStatement(cond != null ? cond : new com.bingbaihanji.bdec.ast.expr.LitExpr(true, JavaType.BOOLEAN),
                        thenBody != null ? thenBody : new BlockStatement(List.of()),
                        elseBody);

                // 如有前导语句,将其前置到 IfStatement 之前
                if (!preIfStmts.isEmpty()) {
                    List<Statement> combined = new ArrayList<>(preIfStmts);
                    combined.add(s);
                    s = new BlockStatement(combined);
                }
            }
            // 循环:将组包装为 LoopStatement(仅在存在有效循环体时)
            else if (loopInfo != null) {
                // 仅处理器的"循环"(来自自引用异常边):
                // 翻译时不包装,以便 stripDuplicatedFinally 可以后续将其吸收到 finally 块中.
                boolean isHandlerLoop = handlerBlocks.containsAll(group.blocks());
                s = translateGroup(group, ir);
                if (s != null && !isEmptyBlock(s) && !isHandlerLoop) {
                    Expression cond = simplifyCondition(extractCondition(group, ir));
                    // bytecode 中的条件跳转(如 ifeq/iflt)表示"满足条件时跳转到循环出口".
                    // 但 Java while 循环的条件表示"满足条件时继续循环".
                    // 因此需要取反:while-loop 条件 = NOT(bytecode jump condition).
                    // 例如 ifeq exit 表示 value==0 时退出,while 条件应为 value!=0.
                    if (cond != null) {
                        cond = new UnExpr(UnaryOperator.NOT, cond);
                        cond = simplifyCondition(cond);
                    }
                    s = new LoopStatement(LoopStatement.LoopKind.WHILE,
                            cond != null ? cond : new VarExpr("true"), s);
                }
            }
            // switch
            else if (switchInfo != null) {
                s = buildSwitch(switchInfo, group, ir, groups, consumed);
            }
            // synchronized:优先级高于 try-catch.
            // synchronized 块的异常处理器(monitorexit + athrow)是 JVM 实现细节——
            // 必须在输出中隐藏.
            else if (groupHasSynchronizedAnnotation(group, ir)) {
                s = translateGroup(group, ir);
                if (s != null) {
                    s = wrapSynchronized(s, group, ir);
                }
            }
            // try-catch:延迟到后处理阶段(wrapTryCatchBlocks)进行包装.
            // 这确保了包含 if/else/loop 结构的 try 范围在内部结构构建完成后才被正确包装.
            else if (tryCatchInfo != null) {
                s = translateGroup(group, ir);
            }
            // 仅处理器的组(纯异常处理器)将被 wrapTryCatchBlocks 吸收到
            // try-finally 中——跳过它们以避免死代码.
            else if (group.blocks().size() == 1 && handlerBlocks.contains(group.first())) {
                continue; // 跳过处理器块——由 try-finally 吸收
            }
            // 普通顺序块
            else {
                s = translateGroup(group, ir);
            }

            if (s != null) {
                statements.add(s);
            }
        }
        // 后处理:将孤立的 ExpressionStatement 转换为 ReturnStatement.
        // 当非 void 表达式成为孤立(未被消费)时,将其包装为 return.
        // 跳过 void 表达式(例如孤立的 lock.unlock() 调用).
        if (!statements.isEmpty() && ir.method().returnType() != null
                && ir.method().returnType().kind() != TypeKind.VOID) {
            for (int i = statements.size() - 1; i >= 0; i--) {
                Statement s = statements.get(i);
                if (s instanceof ExpressionStatement es
                        && es.expression() != null
                        && !isIgnorableExpr(es.expression())
                        && !isVoidExpr(es.expression())
                        && !isAssignExpr(es.expression())) {
                    statements.set(i, new ReturnStatement(es.expression()));
                    break;
                }
                if (s instanceof BlockStatement bs && !bs.statements().isEmpty()) {
                    Statement last = bs.statements().get(bs.statements().size() - 1);
                    if (last instanceof ExpressionStatement es
                            && es.expression() != null
                            && !isIgnorableExpr(es.expression())
                            && !isVoidExpr(es.expression())
                            && !isAssignExpr(es.expression())) {
                        List<Statement> newStmts = new ArrayList<>(bs.statements());
                        newStmts.set(newStmts.size() - 1,
                                new ReturnStatement(es.expression()));
                        statements.set(i, new BlockStatement(newStmts));
                        break;
                    }
                }
                if (!(s instanceof BlockStatement bs && bs.statements().isEmpty())) {
                    break;
                }
            }
        }

        // 后处理:根据注解将语句组包装为 try-catch 结构.
        // 过滤掉可能从重写器或结构化边角情况泄露的 null 语句,
        // null 条目会导致下游 NPE.
        statements = statements.stream()
                .filter(Objects::nonNull)
                .toList();

        // 避免双重包装:如果仅有一条语句且已是 BlockStatement,
        // 则直接使用它作为根而不是再次嵌套.
        BlockStatement root;
        if (statements.size() == 1 && statements.getFirst() instanceof BlockStatement bs) {
            root = bs;
        } else {
            root = new BlockStatement(statements);
        }
        root = wrapTryCatchBlocks(root, groups, tryCatchAnns, ir);
        // 后处理:剥离 synchronized 前导变量(DUP/ASTORE 伪影,
        // 这些是 SynchronizedStatement 之前设置监视器对象的代码)
        root = stripSyncPreambles(root);
        return root;
    }

    /** 在组中查找任意块是否具有 IfInfo 注解 */
    private IfInfo findIfAnnotation(BlockGroup group, Map<BasicBlock, IfInfo> ifAnns) {
        for (BasicBlock b : group.blocks()) {
            if (ifAnns.containsKey(b)) {
                return ifAnns.get(b);
            }
        }
        return null;
    }

    private LoopInfo findLoopAnnotation(BlockGroup group, Map<BasicBlock, LoopInfo> loopAnns) {
        for (BasicBlock b : group.blocks()) {
            if (loopAnns.containsKey(b)) {
                return loopAnns.get(b);
            }
        }
        return null;
    }

    private TryCatchInfo findTryAnnotation(BlockGroup group, Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        for (BasicBlock b : group.blocks()) {
            if (tryCatchAnns.containsKey(b)) {
                return tryCatchAnns.get(b);
            }
        }
        return null;
    }

    private SwitchInfo findSwitchAnnotation(BlockGroup group, Map<BasicBlock, SwitchInfo> switchAnns) {
        for (BasicBlock b : group.blocks()) {
            if (switchAnns.containsKey(b)) {
                return switchAnns.get(b);
            }
        }
        return null;
    }

    /**
     * 直接根据 CFG 结构检测 if-header,绕过 BranchAnalyzer.
     * 检查条件:组的最后一个块具有 CONDITION 指令,且恰好有 2 条
     * TRUE_BRANCH/FALSE_BRANCH 出边.
     *
     * <p>使用后支配树来找到正确的合并点(follow),而非硬编码 Exit.
     * 这对于正确的 if-else 检测至关重要:没有正确的 follow,
     * 两个分支块集合都会包含 if 之后的所有代码,导致第一个分支消费
     * 所有组而为第二个分支留下空集.
     */
    private IfInfo detectIfHeader(BlockGroup group, ControlFlowGraph graph, LinearIr ir,
                                  PostDominatorTree postDom) {
        for (BasicBlock b : group.blocks()) {
            // 检查此块中是否有任何 CONDITION 指令
            boolean hasCondition = ir.instructionsOf(b).stream()
                    .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
            if (!hasCondition) {
                continue;
            }

            // 查找 TRUE_BRANCH 和 FALSE_BRANCH 边
            BasicBlock trueTarget = null, falseTarget = null;
            for (var edge : graph.outgoingOf(b)) {
                if (edge.kind() == EdgeKind.TRUE_BRANCH) {
                    trueTarget = edge.target();
                } else if (edge.kind() == EdgeKind.FALSE_BRANCH) {
                    falseTarget = edge.target();
                }
            }
            if (trueTarget == null && falseTarget == null) {
                continue;
            }
            // 用剩余后继填补缺失的目标
            List<BasicBlock> succs = graph.successorsOf(b);
            if (trueTarget == null && !succs.isEmpty()) {
                for (BasicBlock s : succs) {
                    if (s != falseTarget) {
                        trueTarget = s;
                        break;
                    }
                }
            }
            if (falseTarget == null && !succs.isEmpty()) {
                for (BasicBlock s : succs) {
                    if (s != trueTarget) {
                        falseTarget = s;
                        break;
                    }
                }
            }
            if (trueTarget == null || falseTarget == null) {
                continue;
            }

            // 使用后支配树计算合并点:从条件块出发所有路径都必须经过的首个块.
            // 对于 if-return-else-return,这是 Exit 本身.
            // 对于带合并的 if-else,这是汇聚点.
            BasicBlock follow = postDom.immediatePostDominator(b);
            if (follow == null) {
                follow = graph.exitBlock();
            }

            // 若某一后继即为 follow,则是 if-then(无 else)
            // 若两个后继均不是 follow,则两个分支最终都到达 follow → if-else
            Set<BasicBlock> thenBlocks, elseBlocks;
            boolean negateCondition = false;
            if (trueTarget == follow) {
                // true 分支(跳转目标)直达 follow → false 分支(直落)是 "then" 体
                // 需要取反条件:CONDITION 已经将 ifeq 翻译为 !(值),但
                // then 体是来自 false 分支的代码,应执行 CONDITION 为假时的操作.
                // 因此需再次取反:!(CONDITION) = 原始 boolean 值.
                thenBlocks = collectReachableBlocks(falseTarget, follow, graph);
                elseBlocks = Set.of();
                negateCondition = true;
            } else if (falseTarget == follow) {
                // false 分支直达 follow → true 分支是 "then" 体
                thenBlocks = collectReachableBlocks(trueTarget, follow, graph);
                elseBlocks = Set.of();
            } else {
                // 两个分支都到达 follow → if-else
                thenBlocks = collectReachableBlocks(trueTarget, follow, graph);
                elseBlocks = collectReachableBlocks(falseTarget, follow, graph);
            }
            return new IfInfo(b, follow, thenBlocks, elseBlocks, negateCondition);
        }
        return null;
    }

    /** 收集从 start 出发可达,但不包含 stop 的所有块.
     *  仅沿非异常边(FALL_THROUGH,TRUE_BRANCH,FALSE_BRANCH,BACK_EDGE)遍历.
     *  绝不能沿异常边遍历,否则处理器块会被错误地包含在 if/else 分支体中. */
    private Set<BasicBlock> collectReachableBlocks(BasicBlock start, BasicBlock stop,
                                                   ControlFlowGraph graph) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock curr = queue.poll();
            if (curr == stop || !result.add(curr)) {
                continue;
            }
            for (var edge : graph.outgoingOf(curr)) {
                if (edge.kind() == EdgeKind.EXCEPTION) {
                    continue; // 跳过异常边——处理器块不属于分支
                }
                BasicBlock succ = edge.target();
                if (succ != stop) {
                    queue.add(succ);
                }
            }
        }
        return result;
    }

    /** 翻译分支体内的单个组,递归检测嵌套的 if-else 结构.
     *  对该组使用 detectIfHeader 判断其是否为嵌套条件头. */
    private Statement translateBranchGroup(BlockGroup group, LinearIr ir,
                                           List<BlockGroup> allGroups,
                                           Set<BlockGroup> consumed,
                                           ControlFlowGraph graph,
                                           PostDominatorTree postDom) {
        // 尝试检测此组是否为嵌套 if-header
        IfInfo nestedIf = detectIfHeader(group, graph, ir, postDom);
        if (nestedIf != null) {
            Expression cond = simplifyCondition(extractCondition(group, ir));

            // 翻译头部中的前导条件语句
            List<Statement> preIfStmts = translateHeaderNonCondition(group, ir);

            Statement thenBody = translateBranchBody(nestedIf.thenBlocks(), allGroups,
                    ir, consumed, graph, postDom);
            Statement elseBody = null;
            if (!nestedIf.elseBlocks().isEmpty()) {
                elseBody = translateBranchBody(nestedIf.elseBlocks(), allGroups,
                        ir, consumed, graph, postDom);
            }
            if (isEmptyBlock(elseBody)) {
                elseBody = null;
            }

            // 后处理分支体
            boolean thenHasReturn = hasReturnStmt(thenBody);
            boolean elseHasReturn = hasReturnStmt(elseBody);
            boolean isBoolRet = ir.method().returnType() != null
                    && ir.method().returnType().kind() == TypeKind.BOOLEAN;
            boolean isVoidRet = ir.method().returnType() != null
                    && ir.method().returnType().kind() == TypeKind.VOID;
            if (thenHasReturn != elseHasReturn) {
                if (thenHasReturn) {
                    thenBody = stripOrphanExprs(thenBody);
                    if (elseBody != null) {
                        elseBody = wrapAsReturn(elseBody, isBoolRet, isVoidRet);
                    }
                } else {
                    if (thenBody != null) {
                        thenBody = wrapAsReturn(thenBody, isBoolRet, isVoidRet);
                    }
                    elseBody = stripOrphanExprs(elseBody);
                }
            }

            Statement ifStmt = new IfStatement(
                    cond != null ? cond : new com.bingbaihanji.bdec.ast.expr.LitExpr(true, JavaType.BOOLEAN),
                    thenBody != null ? thenBody : new BlockStatement(List.of()),
                    elseBody);

            if (!preIfStmts.isEmpty()) {
                List<Statement> combined = new ArrayList<>(preIfStmts);
                combined.add(ifStmt);
                return new BlockStatement(combined);
            }
            return ifStmt;
        }

        // 非嵌套 if-header——按普通方式翻译
        return translateGroup(group, ir);
    }

    /**
     * 翻译 if 语句的某一分支(then 或 else)所对应的块.
     * 消费匹配的组以避免重复输出.
     *
     * <p>检查组中任意块是否属于分支(而非仅检查第一个块),
     * 使得 CFG 折叠后以非分支块开头的组仍能被正确匹配.
     *
     * <p>递归检测分支体内的嵌套 if-else/loop 模式.
     */
    private Statement translateBranchBody(Set<BasicBlock> branchBlocks,
                                          List<BlockGroup> allGroups,
                                          LinearIr ir,
                                          Set<BlockGroup> consumed,
                                          ControlFlowGraph graph,
                                          PostDominatorTree postDom) {
        // 设置分支上下文用于 PHI 解析
        Set<Integer> prevBranchBlocks = currentBranchBlocks;
        Set<Integer> branchBlockIds = new HashSet<>();
        for (BasicBlock b : branchBlocks) {
            branchBlockIds.add(b.id());
        }
        currentBranchBlocks = branchBlockIds;
        // 为此分支体压入新的变量声明作用域
        declaredVarNameStack.push(new HashSet<>());
        try {
            List<Statement> bodyStmts = new ArrayList<>();
            for (BlockGroup g : allGroups) {
                if (consumed.contains(g)) {
                    continue;
                }
                boolean groupInBranch = branchBlocks.contains(g.first());
                if (!groupInBranch) {
                    for (BasicBlock gb : g.blocks()) {
                        if (branchBlocks.contains(gb)) {
                            groupInBranch = true;
                            break;
                        }
                    }
                }
                if (groupInBranch) {
                    consumed.add(g);
                    // 递归检测分支内的嵌套 if-else
                    Statement stmt = translateBranchGroup(g, ir, allGroups, consumed, graph, postDom);
                    if (stmt != null) {
                        bodyStmts.add(stmt);
                    }
                }
            }
            if (bodyStmts.isEmpty()) {
                return new BlockStatement(List.of());
            }
            if (bodyStmts.size() == 1 && !(bodyStmts.getFirst() instanceof BlockStatement)) {
                return bodyStmts.getFirst();
            }
            List<Statement> flat = new ArrayList<>();
            for (Statement s : bodyStmts) {
                if (s instanceof BlockStatement bs) {
                    flat.addAll(bs.statements());
                } else {
                    flat.add(s);
                }
            }
            // 后处理:修复跨组的重复变量声明.
            // 当同一分支中的多个 BlockGroup 各自声明了相同的变量时
            //(例如默认初始化后跟真正的初始化),将第一次出现转为普通赋值.
            Set<String> seenBranchDecls = new HashSet<>();
            for (int i = 0; i < flat.size(); i++) {
                if (flat.get(i) instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd) {
                    if (!seenBranchDecls.add(vd.name()) && vd.initializer() != null) {
                        flat.set(i, new com.bingbaihanji.bdec.ast.stmt.ExpressionStatement(
                                new com.bingbaihanji.bdec.ast.expr.AssignExpr(
                                        new com.bingbaihanji.bdec.ast.expr.VarExpr(vd.name()),
                                        vd.initializer())));
                    }
                }
            }
            // 后处理:剥离 RETURN/THROW 之后的不可达语句
            for (int i = 0; i < flat.size(); i++) {
                Statement s = flat.get(i);
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement
                        || s instanceof com.bingbaihanji.bdec.ast.stmt.ThrowStatement
                        || s.kind() == com.bingbaihanji.bdec.ast.AstKind.BREAK
                        || s.kind() == com.bingbaihanji.bdec.ast.AstKind.CONTINUE) {
                    if (i + 1 < flat.size()) {
                        flat = new ArrayList<>(flat.subList(0, i + 1));
                        break;
                    }
                }
            }
            if (flat.size() == 1) {
                return flat.getFirst();
            }
            return new BlockStatement(flat);
        } finally {
            currentBranchBlocks = prevBranchBlocks;
            declaredVarNameStack.pop(); // 弹出分支作用域
        }
    }

    /** 检查变量名是否已在当前作用域(或任何父作用域)中声明,
     *  并标记为已声明. */
    private boolean tryDeclareVar(String name) {
        Set<String> currentScope = declaredVarNameStack.peek();
        if (currentScope == null) {
            return false;
        }
        return currentScope.add(name);
    }

    /** 按支配树先序遍历排序基本块 */
    private List<BasicBlock> dominatorTreeOrder(ControlFlowGraph graph) {
        DominatorTree dom = graph.dominatorTree();
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        Deque<BasicBlock> stack = new ArrayDeque<>();
        stack.push(graph.entryBlock());
        while (!stack.isEmpty()) {
            BasicBlock b = stack.pop();
            if (!visited.add(b)) {
                continue;
            }
            if (b != graph.entryBlock() && b != graph.exitBlock()
                    && !b.instructions().isEmpty()) {
                result.add(b);
            }
            // 逆序压入子节点,确保第一个子节点最先被处理
            List<BasicBlock> children = new ArrayList<>(dom.children(b));
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }

    /**
     * 翻译 if-header 组中非条件的,有副作用的语句.
     * 这些指令在条件检查之前执行,应在输出中出现在 IfStatement 之前.
     */
    private List<Statement> translateHeaderNonCondition(BlockGroup group, LinearIr ir) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        if (allInsns.isEmpty()) {
            return List.of();
        }

        // 构建已消费集合(与 translateGroup 逻辑相同)
        Set<Integer> consumed = new HashSet<>();
        Map<Variable, Integer> loadVarToId = new HashMap<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.LOAD && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                loadVarToId.put(v, insn.id());
            }
        }
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                } else if (op instanceof Variable v && loadVarToId.containsKey(v)) {
                    consumed.add(loadVarToId.get(v));
                }
            }
        }

        // 同时构建本组的 varStoreSource 用于内联
        Map<Variable, Value> varStoreSource = new HashMap<>(currentVarStoreSource);
        Set<Integer> storesToSkip = new HashSet<>(currentStoresToSkip);
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                    && insn.operands().get(0) instanceof Variable v
                    && !varStoreSource.containsKey(v)) {
                Value source = insn.operands().get(1);
                int loadCount = 0;
                for (IrInstruction other : allInsns) {
                    if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                            && other.operands().getFirst() instanceof Variable lv
                            && lv.slot() == v.slot() && lv.version() == v.version()) {
                        loadCount++;
                    }
                }
                if (loadCount == 1 && isSimpleValue(source)
                        && globalVarUseCount.getOrDefault(v, 0) == 1) {
                    for (IrInstruction other : allInsns) {
                        if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                                && other.operands().getFirst() instanceof Variable lv
                                && lv.slot() == v.slot() && lv.version() == v.version()) {
                            if (consumed.contains(other.id())) {
                                varStoreSource.put(v, source);
                                storesToSkip.add(insn.id());
                            }
                            break;
                        }
                    }
                }
            }
        }

        // 替换为本地映射
        Map<Variable, Value> prevVarStore = currentVarStoreSource;
        Set<Integer> prevStoreSkip = currentStoresToSkip;
        currentVarStoreSource = Collections.unmodifiableMap(varStoreSource);
        currentStoresToSkip = Set.copyOf(storesToSkip);
        try {
            List<Statement> stmts = new ArrayList<>();
            for (IrInstruction insn : allInsns) {
                // 跳过 CONDITION——它由 extractCondition() 单独提取
                if (insn.opcode() == IrOpcode.CONDITION) {
                    continue;
                }
                if (currentInitToSkip.contains(insn.id())) {
                    continue;
                }
                if (currentStoresToSkip.contains(insn.id())) {
                    continue;
                }
                if (isStatementRoot(insn)) {
                    Statement s = translateStmt(insn);
                    if (s != null) {
                        stmts.add(s);
                    }
                } else if (!consumed.contains(insn.id()) && insn.resultValue() != null) {
                    Expression e = translateExpr(insn);
                    if (e != null && !isIgnorableExpr(e)) {
                        stmts.add(new ExpressionStatement(e));
                    }
                }
            }
            return stmts;
        } finally {
            currentVarStoreSource = prevVarStore;
            currentStoresToSkip = prevStoreSkip;
        }
    }

    /** 从组的 CONDITION IR 指令中提取条件表达式 */
    private Expression extractCondition(BlockGroup group, LinearIr ir) {
        List<IrInstruction> all = group.allIrInstructions(ir);
        for (IrInstruction insn : all) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                return translateExpr(insn);
            }
        }
        return null;
    }

    /** 从 IfInfo 头部块直接提取条件(处理 CFG 块与 IR 块编号不匹配). */
    private Expression extractConditionFromHeader(BasicBlock header, LinearIr ir) {
        for (IrInstruction insn : ir.instructionsOf(header)) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                return translateExpr(insn);
            }
        }
        return null;
    }

    /** 扫描所有组和全部 IR 指令以查找最靠前的 CONDITION.
     *  组内查找和全局 IR 扫描一起执行,确保找到最早的条件指令. */
    private Expression extractConditionFromAllGroups(List<BlockGroup> groups, LinearIr ir) {
        IrInstruction best = null;
        // 从所有组中扫描
        for (BlockGroup g : groups) {
            for (IrInstruction insn : g.allIrInstructions(ir)) {
                if (insn.opcode() == IrOpcode.CONDITION) {
                    if (best == null || insn.sourceOffset() < best.sourceOffset()) {
                        best = insn;
                    }
                }
            }
        }
        // 直接从全部 IR 指令扫描(捕获未被包含在任何组中的 CONDITION)
        for (IrInstruction insn : ir.instructions()) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                if (best == null || insn.sourceOffset() < best.sourceOffset()) {
                    best = insn;
                }
            }
        }
        return best != null ? translateExpr(best) : null;
    }

    /** 简化常见的布尔冗余模式:
     *  {@code x == true} → {@code x}, {@code x != false} → {@code x},
     *  {@code x == false} → {@code !x}, {@code x != true} → {@code !x},
     *  若 x 为布尔值: {@code x == 0} → {@code !x}, {@code x != 0} → {@code x} */
    private Expression simplifyCondition(Expression cond) {
        if (cond == null) {
            return null;
        }
        if (cond instanceof BinExpr bin) {
            // 简化左侧:x == true → x, x != false → x
            Expression left = simplifyCondition(bin.left());
            Expression right = simplifyCondition(bin.right());
            BinaryOperator op = bin.operator();

            // 检查布尔字面量比较
            boolean rightIsTrue = isBooleanLit(right, true);
            boolean rightIsFalse = isBooleanLit(right, false);
            boolean leftIsTrue = isBooleanLit(left, true);
            boolean leftIsFalse = isBooleanLit(left, false);

            if ((op == BinaryOperator.EQ && rightIsTrue)
                    || (op == BinaryOperator.NE && rightIsFalse)) {
                return left;
            }
            if ((op == BinaryOperator.EQ && rightIsFalse)
                    || (op == BinaryOperator.NE && rightIsTrue)) {
                return new UnExpr(UnaryOperator.NOT, left);
            }
            if ((op == BinaryOperator.EQ && leftIsTrue)
                    || (op == BinaryOperator.NE && leftIsFalse)) {
                return right;
            }
            if ((op == BinaryOperator.EQ && leftIsFalse)
                    || (op == BinaryOperator.NE && leftIsTrue)) {
                return new UnExpr(UnaryOperator.NOT, right);
            }

            // 用简化后的子表达式重建
            if (left != bin.left() || right != bin.right()) {
                return new BinExpr(op, left, right);
            }
        }
        // !!x → x (双重否定消除)
        if (cond instanceof UnExpr un && un.operator() == UnaryOperator.NOT
                && un.operand() instanceof UnExpr inner
                && inner.operator() == UnaryOperator.NOT) {
            return simplifyCondition(inner.operand());
        }
        return cond;
    }

    /**
     * 将相邻基本块聚合为组.
     * 相邻条件:前驱仅有一条 fallthrough 出边指向后继,
     * 且两者具有相同的异常覆盖范围——因为 try 前代码(如 lock.lock())
     * 与 try 体合并后将无法正确包装.
     */
    private List<BlockGroup> groupAdjacentBlocks(List<BasicBlock> blocks, ControlFlowGraph graph,
                                                 Map<BasicBlock, LoopInfo> loopAnns) {
        List<BlockGroup> groups = new ArrayList<>();
        BlockGroup current = null;
        for (BasicBlock b : blocks) {
            if (current == null) {
                current = new BlockGroup(b);
            } else if (isAdjacent(current.last(), b, graph, loopAnns)) {
                current.add(b);
            } else {
                groups.add(current);
                current = new BlockGroup(b);
            }
        }
        if (current != null) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 检查两个基本块是否应为相邻关系(同一组).
     * 相邻块之间具有从前驱到后继的单条 fallthrough 边,
     * 且具有相同的异常覆盖范围——不同异常处理器的块不应被合并,
     * 否则 try 前代码(如 lock.lock())会与 try 体合并而无法正确包装.
     *
     * <p>同时检查循环边界:不将前导块与循环头块合并,
     * 否则循环初始化代码会被错误地包含在循环体内.
     */
    private boolean isAdjacent(BasicBlock prev, BasicBlock next, ControlFlowGraph graph,
                               Map<BasicBlock, LoopInfo> loopAnns) {
        List<BasicBlock> succs = graph.successorsOf(prev);
        if (succs.size() != 1 || succs.get(0) != next) {
            return false;
        }
        if (!graph.outgoingOf(prev).stream().allMatch(e -> e.kind() == EdgeKind.FALL_THROUGH)) {
            return false;
        }
        // 尊重 try 边界:如果两个块具有不同的异常覆盖范围
        //(一个有异常边而另一个没有),则不合并它们.
        boolean prevHasException = graph.outgoingOf(prev).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        boolean nextHasException = graph.outgoingOf(next).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        if (prevHasException != nextHasException) {
            return false;
        }
        // 尊重循环边界:如果后继块是循环头,不要将前导块(循环初始化代码)
        // 与循环体合并.检查 next 块本身及其内部所有块.
        if (loopAnns != null) {
            for (BasicBlock b : loopAnns.keySet()) {
                if (b == next || b.id() == next.id()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 将块组翻译为语句树.
     *
     * <p>仅输出有副作用的指令(语句).中间值指令(LOAD,BINARY 等)被跳过——
     * 它们通过递归的 {@link #valueToExpr} 解析参与表达式树的构建.
     */
    private Statement translateGroup(BlockGroup group, LinearIr ir) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        if (allInsns.isEmpty()) {
            return null;
        }

        // 构建索引:记录哪些指令 ID 的结果被消费.
        // 通过 InstructionRef(标准链)和 Variable(LOAD 指令的结果变量
        // 直接流经栈的情况)进行追踪.
        Set<Integer> consumed = new HashSet<>();
        // 将每个 LOAD 指令加载的变量映射到 LOAD ID 以备回溯
        Map<Variable, Integer> loadVarToId = new HashMap<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.LOAD && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                loadVarToId.put(v, insn.id());
            }
        }
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                } else if (op instanceof Variable v && loadVarToId.containsKey(v)) {
                    // LOAD 产生的变量被直接使用 → 标记 LOAD 为已消费
                    consumed.add(loadVarToId.get(v));
                }
            }
        }

        // 用逐组发现的结果扩充全局 var→value 内联映射.
        // 从全局映射开始(在 reduce() 中构建),并添加逐组条目.
        Map<Variable, Value> varStoreSource = new HashMap<>(currentVarStoreSource);
        Set<Integer> storableToSkip = new HashSet<>(currentStoresToSkip);
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                    && insn.operands().get(0) instanceof Variable v
                    && !varStoreSource.containsKey(v)) {
                Value source = insn.operands().get(1);
                // 统计此组的 STORE 结果变量被加载的次数
                int loadCount = 0;
                for (IrInstruction other : allInsns) {
                    if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                            && other.operands().getFirst() instanceof Variable lv
                            && lv.slot() == v.slot() && lv.version() == v.version()) {
                        loadCount++;
                    }
                }
                if (loadCount == 1 && isSimpleValue(source)
                        && globalVarUseCount.getOrDefault(v, 0) == 1) {
                    // 检查唯一的 LOAD 是否被消费
                    for (IrInstruction other : allInsns) {
                        if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                                && other.operands().getFirst() instanceof Variable lv
                                && lv.slot() == v.slot() && lv.version() == v.version()) {
                            if (consumed.contains(other.id())) {
                                varStoreSource.put(v, source);
                                storableToSkip.add(insn.id());
                            }
                            break;
                        }
                    }
                }
            }
        }
        currentVarStoreSource = Collections.unmodifiableMap(varStoreSource);
        currentStoresToSkip = Set.copyOf(storableToSkip);

        // 预遍历:合并 NEW + INVOKE <init> 对(CondenseConstruction 模式)
        // 在全局预遍历结果的基础上添加本组内发现的合并对
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.INVOKE && insn.hasTag(
                    com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION)
                    && !insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                    && !insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)) {
                for (Value op : insn.operands()) {
                    if (op instanceof InstructionRef ref) {
                        IrInstruction def = ref.instruction();
                        if (def.opcode() == IrOpcode.NEW && consumed.contains(def.id())
                                && !currentInitToSkip.contains(insn.id())) {
                            currentNewToInit.computeIfAbsent(def.id(),
                                    k -> new java.util.ArrayList<>()).add(insn);
                            currentInitToSkip.add(insn.id());
                            break;
                        }
                    }
                }
            }
        }

        // 检查任意指令是否有 synchronized 块注解
        boolean isSynchronized = allInsns.stream().anyMatch(
                i -> i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK)
                        && i.opcode() == IrOpcode.MONITOR_ENTER);

        // 仅将根指令生成为语句
        List<Statement> stmts = new ArrayList<>();
        for (IrInstruction insn : allInsns) {
            // 跳过条件——它们由 reduce() 中的 IfStatement/LoopStatement 包装器
            // 通过 extractCondition() 提取.如果条件块没有匹配的注解,
            // 则生成注释占位符以免控制流被静默丢失.
            if (insn.opcode() == IrOpcode.CONDITION) {
                // CONDITION 指令由 reduce() 中的 IfStatement/LoopStatement 包装器
                // 通过 extractCondition() 提取.在此处静默跳过——
                // 条件块总是在组级别进行结构化.
                continue;
            }

            // 跳过合成的 $assertionsDisabled 字段存储——这些是 JVM 断言伪影,
            // 在源码中并不存在.
            if (insn.opcode() == IrOpcode.FIELD_STORE
                    && "$assertionsDisabled".equals(insn.nameHint())) {
                continue;
            }

            // this$X 字段现在保留在输出中,其存储操作也需保留

            // 跳过对 java.lang.Object 的隐式 super() 调用.
            // 在字节码中每个构造函数都以 INVOKESPECIAL Object.<init>() 开始,
            // 但 Java 源码并不会显式写出.
            if (insn.opcode() == IrOpcode.INVOKE
                    && insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)) {
                // 检查声明类是否为 java/lang/Object(即隐式 super 调用)
                String declaringClass = null;
                for (var ann : insn.annotations()) {
                    if (ann.is(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                        declaringClass = ann.getString(
                                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                    }
                }
                if ("java/lang/Object".equals(declaringClass)) {
                    continue; // 跳过隐式 super()——在 Java 源码中不出现
                }
            }

            // 跳过已合并到 NEW 中的 INIT 调用
            if (currentInitToSkip.contains(insn.id())) {
                continue;
            }

            // 跳过已被内联的 STORE 指令
            if (currentStoresToSkip.contains(insn.id())) {
                continue;
            }

            // 仅输出有副作用的指令作为语句
            if (isStatementRoot(insn)) {
                Statement s = translateStmt(insn);
                if (s != null) {
                    stmts.add(s);
                }
            } else if (!consumed.contains(insn.id()) && insn.resultValue() != null) {
                // 独立表达式(结果未被任何指令消费)——仍输出
                Expression e = translateExpr(insn);
                if (e != null && !isIgnorableExpr(e)) {
                    stmts.add(new ExpressionStatement(e));
                }
            }
        }

        if (stmts.isEmpty()) {
            return new BlockStatement(List.of());
        }

        // 后处理:修复同一块中的重复变量声明.
        // 当作用域定位失败时,同一变量可能获得多个 "Type name = ..." 声明.
        // 将重复声明转换为普通赋值.
        Set<String> seenDecls = new HashSet<>();
        for (int i = 0; i < stmts.size(); i++) {
            if (stmts.get(i) instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd) {
                if (!seenDecls.add(vd.name()) && vd.initializer() != null) {
                    // 重复声明 → 转为普通赋值
                    stmts.set(i, new ExpressionStatement(
                            new AssignExpr(new VarExpr(vd.name()), vd.initializer())));
                }
            }
        }

        // 后处理:剥离 RETURN/THROW/BREAK/CONTINUE 之后的不可达语句.
        // 当 CFG 结构化失败时,无条件控制转移指令后面会跟着死代码,导致编译错误.
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof ReturnStatement || s instanceof ThrowStatement
                    || s.kind() == com.bingbaihanji.bdec.ast.AstKind.BREAK
                    || s.kind() == com.bingbaihanji.bdec.ast.AstKind.CONTINUE) {
                if (i + 1 < stmts.size()) {
                    stmts = new ArrayList<>(stmts.subList(0, i + 1));
                    break;
                }
            }
        }

        // 后处理:抑制 this()/super() 构造函数委托调用之后的多余 "return;".
        // 在字节码中构造函数总是以 RETURN 结尾,但 Java 源码中不需要
        // 在 this()/super() 调用之后出现 "return;".
        if (!stmts.isEmpty()) {
            Statement last = stmts.get(stmts.size() - 1);
            if (last instanceof ReturnStatement r && r.value() == null) {
                boolean hasCtorDeleg = allInsns.stream().anyMatch(i ->
                        i.opcode() == IrOpcode.INVOKE
                                && (i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                                || i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)));
                if (hasCtorDeleg) {
                    stmts.remove(stmts.size() - 1);
                }
            }
        }

        if (stmts.size() == 1) {
            return stmts.getFirst();
        }
        return new BlockStatement(stmts);
    }

    /**
     * 将单条 IR 指令翻译为 AST 语句.
     *
     * <ul>
     *   <li>MONITOR_ENTER/EXIT 由 SynchronizedRecognizer → SynchronizedStatement 处理</li>
     *   <li>RETURN:对 <clinit> 抑制 "return;"(JVM 伪影)</li>
     *   <li>THROW:必要时引入临时变量声明</li>
     *   <li>STORE:首个定义版本 emit "Type name = value;",后续转为赋值</li>
     * </ul>
     */
    private Statement translateStmt(IrInstruction insn) {
        return switch (insn.opcode()) {
            // MONITOR_ENTER/EXIT 由 SynchronizedRecognizer → SynchronizedStatement 处理.
            // 如果在此处出现未处理的实例,跳过它们而非生成非法语法.
            case MONITOR_ENTER, MONITOR_EXIT -> null;
            case RETURN -> {
                if (insn.operands().isEmpty()) {
                    // 静态初始化器(<clinit>)不应输出 "return;" ——
                    // 它只是 JVM 伪影,不是合法的 Java 源码.
                    if (currentIr != null && "<clinit>".equals(currentIr.method().name())) {
                        yield null;
                    }
                    yield new ReturnStatement(null);
                } else {
                    Expression retVal = valueToExpr(insn.operands().getFirst());
                    // 应用语义注解中的布尔折叠
                    retVal = applyBooleanAnnotation(insn, retVal);
                    // 对 boolean 返回方法,将整数字面量转为布尔值
                    //(PHI 解析后的值可能跳过了注解)
                    if (currentMethodReturnsBoolean
                            && retVal instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                            && lit.value() instanceof Integer i) {
                        retVal = new com.bingbaihanji.bdec.ast.expr.LitExpr(
                                i != 0, JavaType.BOOLEAN);
                    }
                    yield new ReturnStatement(retVal);
                }
            }
            case THROW -> {
                Expression thrown = translateExpr(insn);
                if (thrown instanceof VarExpr v && v.name().startsWith("var")
                        && tryDeclareVar("$exc$" + v.name())) {
                    yield new com.bingbaihanji.bdec.ast.stmt.BlockStatement(List.of(
                            new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                                    JavaType.classType("java/lang/Throwable"),
                                    v.name(), null),
                            new ThrowStatement(thrown)));
                }
                yield new ThrowStatement(thrown);
            }
            case STORE -> {
                // 对每个逻辑变量的首次存储产生 "Type name = value;".
                // 同时使用 slot+version:任意 slot 上的 version 1 总是首个局部变量定义
                //(version 0 = 参数).同时使用按作用域的追踪,
                // 使相同 slot 上不同分支体的临时变量各自获得独立的声明.
                Value target = insn.operands().getFirst();
                if (target instanceof Variable v && !v.isParameter()
                        && v.slot() != 0) {
                    String declName = v.name();
                    // version 1 = 此 slot 的首个局部变量 → 始终声明.
                    // version 2+ = 重新赋值 → 仅在新作用域中声明.
                    // 始终调用 tryDeclareVar 以在作用域中追踪该变量名.
                    boolean isFirstDef = v.version() == 1
                            || tryDeclareVar(declName);
                    if (v.version() == 1) {
                        tryDeclareVar(declName); // 在作用域中追踪
                    }
                    if (isFirstDef) {
                        Value source = insn.operands().size() > 1
                                ? insn.operands().get(1) : null;
                        Expression rhs = source != null
                                ? valueToExpr(source) : null;
                        yield new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                                v.type(), declName, rhs);
                    }
                }
                Expression e = translateExpr(insn);
                yield e != null ? new ExpressionStatement(e) : null;
            }
            default -> {
                Expression e = translateExpr(insn);
                yield e != null ? new ExpressionStatement(e) : null;
            }
        };
    }

    /**
     * 将 invokedynamic INVOKE 翻译为 LambdaExpr.
     *
     * <p>检测 lambda 表达式(lambda$method$N 模式)和方法引用.
     * 对于字符串拼接(makeConcatWithConstants),委托到普通的 INVOKE 处理.
     *
     * <p>检测优先级:
     * <ol>
     *   <li>引导方法解析:检查实现方法句柄.
     *       如果指向合成的 {@code lambda$xxx$N} 方法 → 表达式/块 lambda.
     *       如果指向真实方法 → 方法引用({@code Class::method}).
     *       如果指向 {@code <init>} → 构造方法引用({@code Class::new}).</li>
     *   <li>基于名称的模式:名称中的 "::" 或 "new " 前缀.</li>
     *   <li>SAM 名称启发式:如果 indy 名称是函数式接口方法名且无已解析信息,猜测方法引用.</li>
     * </ol>
     */
    private Expression translateIndyInvoke(IrInstruction insn) {
        String mName = insn.nameHint() != null ? insn.nameHint() : "lambda";
        JavaType funcType = insn.resultType();
        List<Value> operands = insn.operands();

        // 字符串拼接:交由 StringConcatRewriter 后续处理
        if (mName.contains("Concat") || mName.contains("concat")) {
            return translateIndyAsRegularInvoke(insn);
        }

        // 从语义注解中读取已解析的引导方法信息
        String implName = getIndyAnnotation(insn, "implName");
        String implOwner = getIndyAnnotation(insn, "implOwner");
        String implDescriptor = getIndyAnnotation(insn, "implDescriptor");

        // 从操作数构建参数列表(捕获变量 + 工厂参数 → lambda 参数)
        List<LambdaExpr.Param> params = buildIndyParams(operands);

        // 当 INDY 操作数无法提供参数信息时(无捕获变量的 lambda),
        // 使用实现方法描述符生成带类型的参数占位符,
        // 确保 LambdaRewriter.buildLambdaBody 能正确替换
        if (params.isEmpty() && implDescriptor != null && !implDescriptor.isEmpty()) {
            params = buildParamsFromDescriptor(implDescriptor);
        }

        // 检测方法引用模式(名称包含 "::" 或以 "new" 开头)
        if (mName.contains("::")) {
            String[] parts = mName.split("::", 2);
            return LambdaExpr.methodRef(parts[0], parts.length > 1 ? parts[1] : "new",
                    funcType);
        }

        // 通过 "new" 前缀的方法引用
        if (mName.startsWith("new ")) {
            String cls = mName.substring(4);
            return LambdaExpr.methodRef(cls, "new", funcType);
        }

        // 已解析的引导方法信息:区分 lambda 和方法引用
        if (implName != null && !implName.isEmpty() && implOwner != null) {
            if (implName.startsWith("lambda$")) {
                // Lambda:实现是合成的 lambda$xxx$N 方法
                String bodyHint = "/* " + implName + " */";
                return LambdaExpr.placeholder(params, bodyHint, funcType);
            }
            if ("<init>".equals(implName)) {
                // 构造方法引用:ClassName::new
                return LambdaExpr.methodRef(simplifyClassName(implOwner), "new", funcType);
            }
            // 方法引用:确定 owner 的表示方式
            String owner = simplifyClassName(implOwner);
            // 对于带捕获接收者的实例方法引用,使用变量名
            if (!operands.isEmpty() && operands.getFirst() instanceof Variable v
                    && v.name() != null && !"this".equals(v.name())) {
                owner = v.name();
            }
            return LambdaExpr.methodRef(owner, implName, funcType);
        }

        // 回退启发式:如果名称是 SAM 方法名(非 lambda$),且返回类型类似函数式接口,猜测方法引用
        if (isSamMethodName(mName) && isFunctionalInterfaceLike(funcType)) {
            String owner = functionalInterfaceShortName(funcType);
            if (owner != null && !owner.isEmpty()) {
                return LambdaExpr.methodRef(owner, mName, funcType);
            }
        }

        // Lambda 占位符回退
        String bodyHint = "/* " + mName + " */";
        return LambdaExpr.placeholder(params, bodyHint, funcType);
    }

    /** 从 INDY 操作数构建参数列表 */
    private List<LambdaExpr.Param> buildIndyParams(List<Value> operands) {
        List<LambdaExpr.Param> params = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Value op = operands.get(i);
            JavaType pt = op.type();
            String pName = "arg" + i;
            if (op instanceof Variable v) {
                String vn = v.name();
                if (vn != null && !vn.startsWith("var") && !"this".equals(vn)) {
                    pName = vn;
                }
            }
            params.add(new LambdaExpr.Param(pName, pt));
        }
        return params;
    }

    /**
     * 从实现方法描述符构建带类型的参数占位符.
     * 用于无捕获变量的 lambda,此时 INDY 操作数为空,
     * 无法从操作数推导参数类型.此方法从实现方法描述符(如 "(II)I")
     * 中解析出参数类型,为每个参数创建带类型和通用名称的占位符.
     *
     * @param methodDescriptor 实现方法的 JVM 描述符(如 "(II)I")
     * @return 参数列表,仅含类型和通用名称(arg0,arg1,...)
     */
    private static List<LambdaExpr.Param> buildParamsFromDescriptor(String methodDescriptor) {
        List<LambdaExpr.Param> params = new ArrayList<>();
        JavaType[] paramTypes = TypeResolver.parseMethodParameterTypes(methodDescriptor);
        for (int i = 0; i < paramTypes.length; i++) {
            params.add(new LambdaExpr.Param("arg" + i, paramTypes[i]));
        }
        return params;
    }

    /** 从 INDY 注解属性中获取字符串值 */
    String getIndyAnnotation(IrInstruction insn, String key) {
        for (com.bingbaihanji.bdec.semantic.SemanticAnnotation ann : insn.annotations()) {
            if (ann.is(com.bingbaihanji.bdec.semantic.SemanticTag.INDY)) {
                String val = ann.getString(key);
                if (val != null && !val.isEmpty()) {
                    return val;
                }
            }
        }
        return null;
    }

    /** 回退方案:将 INDY 当作普通方法调用来处理 */
    private Expression translateIndyAsRegularInvoke(IrInstruction insn) {
        String mName = insn.nameHint() != null ? insn.nameHint() : "method";
        List<Expression> args = new ArrayList<>();
        for (Value op : insn.operands()) {
            args.add(valueToExpr(op));
        }
        return new InvocationExpr(null, mName, args, insn.resultType());
    }

    /**
     * 将单条 IR 指令翻译为 AST 表达式.
     *
     * <p>涵盖所有 IR 操作码类型的表达式翻译:CONST,LOAD,STORE(→赋值),
     * FIELD_LOAD,FIELD_STORE(→字段赋值),BINARY,COMPARE,CONDITION,
     * UNARY,INVOKE(含 INDY 的 lambda 翻译),CAST,NEW,NEW_ARRAY,
     * INSTANCE_OF,ARRAY_LOAD,ARRAY_STORE,ARRAY_LENGTH,INC,THROW,PHI 等.
     */
    private Expression translateExpr(IrInstruction insn) {
        return switch (insn.opcode()) {

            // 常量
            case CONST -> constToExpr(insn);

            // 变量加载
            case LOAD -> {
                if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof Variable v) {
                    yield varToExpr(v);
                }
                yield new VarExpr("var");
            }

            // 变量存储 → 赋值
            case STORE -> {
                Value target = insn.operands().getFirst();
                Value source = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                Expression lhs;
                if (target instanceof Variable v) {
                    lhs = varToExpr(v);
                } else {
                    lhs = valueToExpr(target);
                }
                Expression rhs = source != null ? valueToExpr(source) : new VarExpr("varUnresolved");
                // 复合赋值检测:x = x OP y → x OP= y
                // 检测到时仅使用右操作数(剥离重复的左操作数)
                BinaryOperator compoundOp = detectCompoundOp(lhs, rhs);
                Expression assignRhs = rhs;
                if (compoundOp != null && rhs instanceof BinExpr bin) {
                    assignRhs = bin.right(); // 剥离重复的左操作数
                }
                // x += 1 → x++  /  x -= 1 → x--
                if (compoundOp != null && assignRhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                        && lr.value() instanceof Integer i && i == 1) {
                    if (compoundOp == BinaryOperator.ADD) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, lhs);
                    } else if (compoundOp == BinaryOperator.SUB) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, lhs);
                    }
                }
                // 自增/自减检测:x = x + 1 → x++, x = x - 1 → x--
                if (compoundOp == null && rhs instanceof BinExpr bin
                        && expressionsMatch(lhs, bin.left())) {
                    UnaryOperator incOp = detectIncrement(bin);
                    if (incOp != null) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(incOp, lhs);
                    }
                }
                yield new AssignExpr(lhs, assignRhs, compoundOp);
            }

            // 字段加载——在隐式 'this' 上仅使用字段名,
            // 除非存在同名的局部变量造成歧义.
            // 对于带有 DECLARING_CLASS 标记的静态字段,输出 ClassName.fieldName.
            case FIELD_LOAD -> {
                Expression obj = insn.operands().isEmpty() ? null : valueToExpr(insn.operands().getFirst());
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // 检查是否有声明类注解的静态字段
                if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                    var dcAnn = insn.getAnnotation(
                            com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS);
                    if (dcAnn != null) {
                        String dc = dcAnn.getString(
                                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                        if (dc != null) {
                            int lastSlash = dc.lastIndexOf('/');
                            obj = new VarExpr(lastSlash >= 0
                                    ? dc.substring(lastSlash + 1) : dc);
                        }
                    }
                }
                // 在实例方法中,对 'this' 的字段加载 → 仅使用字段名,
                // 除非局部变量与该字段名冲突(如 "lock = this.lock")
                if (isInstanceMethod && obj instanceof VarExpr v && "this".equals(v.name())) {
                    if (!localVarShadowsField(fName)) {
                        yield new VarExpr(fName);
                    }
                }
                yield new FieldAccessExpr(obj, fName);
            }

            // 字段存储 → 字段赋值
            case FIELD_STORE -> {
                Value obj = !insn.operands().isEmpty() ? insn.operands().getFirst() : null;
                Value val = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // 始终使用 this.fieldName 进行实例字段存储,
                // 使输出能够清晰区分字段赋值和局部变量赋值.
                // 防止将 "this.capacity = x" 错误输出为 "capacity = x".
                Expression lhs;
                if (isInstanceMethod && obj instanceof Variable v && v.slot() == 0) {
                    lhs = new FieldAccessExpr(new VarExpr("this"), fName);
                } else if (obj instanceof Variable v) {
                    lhs = new FieldAccessExpr(varToExpr(v), fName);
                } else {
                    lhs = new FieldAccessExpr(null, fName);
                }
                Expression rhs = val != null ? valueToExpr(val) : new VarExpr("varUnresolved");
                // 字段存储同样应用复合赋值和自增/自减检测
                BinaryOperator compoundOp = detectCompoundOp(lhs, rhs);
                Expression assignRhs = rhs;
                if (compoundOp != null && rhs instanceof BinExpr bin) {
                    assignRhs = bin.right();
                }
                // += 1 → ++, -= 1 → --
                if (compoundOp != null && assignRhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                        && lr.value() instanceof Integer i && i == 1) {
                    if (compoundOp == BinaryOperator.ADD) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, lhs);
                    } else if (compoundOp == BinaryOperator.SUB) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, lhs);
                    }
                }
                if (compoundOp == null && rhs instanceof BinExpr bin
                        && expressionsMatch(lhs, bin.left())) {
                    UnaryOperator incOp = detectIncrement(bin);
                    if (incOp != null) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(incOp, lhs);
                    }
                }
                yield new AssignExpr(lhs, assignRhs, compoundOp);
            }

            // 二元运算——使用原始字节码操作码推断运算符
            case BINARY -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    BinaryOperator binOp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());
                    yield new BinExpr(binOp != null ? binOp : BinaryOperator.ADD, left, right);
                }
                yield new VarExpr("/* binary */");
            }

            // 比较运算
            case COMPARE -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    yield new BinExpr(BinaryOperator.EQ, left, right);
                }
                yield new VarExpr("/* compare */");
            }

            // 条件——使用原始字节码操作码推断比较运算符
            case CONDITION -> {
                if (insn.operands().size() >= 2) {
                    Value leftOp = insn.operands().get(0);
                    Value rightOp = insn.operands().get(1);

                    // 检测布尔变量与 0 的比较:
                    //   boolean == 0 → !boolean,  boolean != 0 → boolean
                    // 此处理用于将 IFEQ/IFNE 字节码转为 if(flag) / if(!flag).
                    // 同时处理返回布尔的函数调用(desiredAssertionStatus 等).
                    boolean leftIsBool = isBooleanValue(leftOp);
                    boolean rightIsBool = isBooleanValue(rightOp);
                    boolean rightIsZero = rightOp instanceof ConstantValue cv
                            && cv.value() instanceof Integer i && i == 0;
                    boolean leftIsZero = leftOp instanceof ConstantValue cv
                            && cv.value() instanceof Integer i && i == 0;

                    BinaryOperator cmpOp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());

                    // 布尔+0 简化:仅当布尔值不来自 COMPARE 时适用.
                    // COMPARE+CONDITION 需要保留原始比较运算符(如 GT),
                    // 由下方的 COMPARE 合并逻辑处理.
                    boolean leftFromCompare = leftOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE;
                    boolean rightFromCompare = rightOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE;

                    if (leftIsBool && rightIsZero && !leftFromCompare) {
                        Expression varExpr = valueToExpr(leftOp);
                        if (cmpOp == BinaryOperator.EQ) {
                            yield new UnExpr(UnaryOperator.NOT, varExpr);
                        } else if (cmpOp == BinaryOperator.NE) {
                            yield varExpr;
                        }
                    }
                    if (rightIsBool && leftIsZero && !rightFromCompare) {
                        Expression varExpr = valueToExpr(rightOp);
                        if (cmpOp == BinaryOperator.EQ) {
                            yield new UnExpr(UnaryOperator.NOT, varExpr);
                        } else if (cmpOp == BinaryOperator.NE) {
                            yield varExpr;
                        }
                    }

                    // 检测 COMPARE+CONDITION 模式
                    Value cmpVal = null;
                    if (rightOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE) {
                        cmpVal = rightOp;
                    } else if (leftOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE) {
                        cmpVal = leftOp;
                    }

                    if (cmpVal != null) {
                        IrInstruction cmp = ((InstructionRef) cmpVal).instruction();
                        if (cmp.operands().size() >= 2) {
                            Expression cmpLeft = valueToExpr(cmp.operands().get(0));
                            Expression cmpRight = valueToExpr(cmp.operands().get(1));
                            BinaryOperator cmpBinOp = IrInstruction.binaryOpFromBytecode(
                                    insn.originalOpcode());
                            if (cmpBinOp != null) {
                                yield new BinExpr(cmpBinOp, cmpLeft, cmpRight);
                            }
                        }
                    }

                    // 常规条件(无 COMPARE 合并)
                    Expression left = valueToExpr(leftOp);
                    Expression right = valueToExpr(rightOp);
                    yield new BinExpr(cmpOp != null ? cmpOp : BinaryOperator.EQ, left, right);
                }
                yield new VarExpr("/* condition */");
            }

            // 一元运算
            case UNARY -> {
                if (!insn.operands().isEmpty()) {
                    UnaryOperator uop = inferUnaryOp(insn.originalOpcode());
                    yield new UnExpr(uop, valueToExpr(insn.operands().getFirst()));
                }
                yield new VarExpr("/* unary */");
            }

            // 方法调用——第一个操作数是接收者(非静态调用时)
            case INVOKE -> {
                // Invokedynamic(lambda / 方法引用):改为生成 LambdaExpr
                if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.INDY)) {
                    yield indyTranslator.translate(insn);
                }

                List<Expression> args = new ArrayList<>();
                boolean isConstructor = insn.hasTag(
                        com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION)
                        || insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                        || insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR);

                String mName = insn.nameHint() != null ? insn.nameHint() : "method";

                // 第一个操作数是接收者(IrBuilder 将其存储为目标)
                int argStart = 0;
                Expression target = null;
                if (isConstructor) {
                    // 构造函数:第一个操作数是 'this' (ALOAD_0)——跳过,使用语义名称
                    argStart = 1;
                    if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)) {
                        mName = "super";
                    } else if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)) {
                        mName = "this";
                    } else {
                        // 对象创建:NEW + INVOKESPECIAL <init> 模式
                        // 将 "<init>" 替换为目标类的简单名称
                        var ann = insn.getAnnotation(
                                com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION);
                        if (ann != null) {
                            String targetClass = ann.getString(
                                    com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_TARGET_CLASS);
                            if (targetClass != null) {
                                int lastSlash = targetClass.lastIndexOf('/');
                                mName = lastSlash >= 0
                                        ? targetClass.substring(lastSlash + 1)
                                        : targetClass;
                            }
                        }
                    }
                } else if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                    // 带声明类注解的静态调用 → 使用类名作为目标
                    var dcAnn = insn.getAnnotation(
                            com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS);
                    if (dcAnn != null) {
                        String dc = dcAnn.getString(
                                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                        if (dc != null) {
                            int lastSlash = dc.lastIndexOf('/');
                            target = new VarExpr(lastSlash >= 0
                                    ? dc.substring(lastSlash + 1) : dc);
                        }
                    }
                    argStart = 0; // 所有操作数均为参数(无接收者)
                } else if (!insn.operands().isEmpty()) {
                    // 普通调用:第一个操作数是接收者 → 转为 target 表达式
                    Value firstOp = insn.operands().getFirst();
                    target = valueToExpr(firstOp);
                    argStart = 1;
                } else {
                    // 无注解的静态调用——无目标
                    argStart = 0;
                }

                for (int i = argStart; i < insn.operands().size(); i++) {
                    args.add(valueToExpr(insn.operands().get(i)));
                }
                yield new InvocationExpr(target, mName, args, insn.resultType());
            }

            // 类型转换
            case CAST -> {
                Expression operand = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("varUnresolved");
                yield new CastExpr(insn.resultType(), operand);
            }

            // 对象创建——若已折叠则携带合并后的构造函数参数
            case NEW -> {
                if (currentNewToInit.containsKey(insn.id())) {
                    List<IrInstruction> inits = currentNewToInit.get(insn.id());
                    List<Expression> ctorArgs = new ArrayList<>();
                    for (IrInstruction init : inits) {
                        for (int i = 0; i < init.operands().size(); i++) {
                            Value op = init.operands().get(i);
                            // 跳过自引用(接收者 = 此 NEW 指令)
                            if (op instanceof InstructionRef ref
                                    && ref.instruction().id() == insn.id()) {
                                continue;
                            }
                            ctorArgs.add(valueToExpr(op));
                        }
                    }
                    // NewExpr 构造函数为 (type, dimensions, constructorArgs)
                    // 注意:不再调用 stripEnclosingThis,因为 AstBuilder 保留了 this$0
                    // 构造函数参数,调用处需传递 this 以保持一致性
                    yield new NewExpr(insn.resultType(), List.of(), ctorArgs);
                }
                // NewExpr 构造函数为 (type, dimensions, constructorArgs)
                yield new NewExpr(insn.resultType(), List.of(), List.of());
            }
            case NEW_ARRAY -> {
                // 从操作数中提取数组大小(由 NEWARRAY/ANEWARRAY 弹出栈的值)
                List<Expression> dims = new ArrayList<>();
                for (Value op : insn.operands()) {
                    dims.add(valueToExpr(op));
                }
                if (dims.isEmpty()) {
                    dims.add(new VarExpr("varUnresolved"));
                }
                yield new NewExpr(insn.resultType(), dims, List.of());
            }

            // instanceof:nameHint 携带目标类内部名
            case INSTANCE_OF -> {
                Expression obj = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("obj");
                JavaType checkedType = insn.nameHint() != null
                        ? JavaType.classType(insn.nameHint())
                        : JavaType.classType("java/lang/Object");
                yield new InstanceOfExpr(obj, checkedType);
            }

            // 数组元素加载:a[i]
            case ARRAY_LOAD -> {
                Expression arr = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().get(0)) : new VarExpr("arr");
                Expression idx = insn.operands().size() > 1
                        ? valueToExpr(insn.operands().get(1)) : new VarExpr("i");
                yield new ArrayAccessExpr(arr, idx);
            }
            // 数组元素存储:a[i] = v
            case ARRAY_STORE -> {
                Expression arr = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().get(0)) : new VarExpr("arr");
                Expression idx = insn.operands().size() > 1
                        ? valueToExpr(insn.operands().get(1)) : new VarExpr("i");
                Expression val = insn.operands().size() > 2
                        ? valueToExpr(insn.operands().get(2)) : new VarExpr("varUnresolved");
                yield new AssignExpr(new ArrayAccessExpr(arr, idx), val);
            }

            // 数组长度
            case ARRAY_LENGTH -> {
                Expression arr = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("arr");
                yield new FieldAccessExpr(arr, "length");
            }

            // 自增指令(IINC)——操作数: [readVar, writeVar, ConstantValue(incr)]
            case INC -> {
                if (insn.operands().size() >= 3 && insn.operands().getFirst() instanceof Variable v) {
                    Value incr = insn.operands().get(2); // 索引 2 是增量值
                    VarExpr var = varToExpr(v);
                    Expression rhs = valueToExpr(incr);
                    // x += c → 若 c == 1 则转为 x++
                    if (rhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                            && lr.value() instanceof Integer i && i == 1) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, var);
                    }
                    yield new AssignExpr(var, new BinExpr(BinaryOperator.ADD, var, rhs));
                }
                yield new VarExpr("/* inc */");
            }

            // 抛出异常
            case THROW -> !insn.operands().isEmpty() ? valueToExpr(insn.operands().getFirst()) : new VarExpr("ex");

            // PHI——选取属于当前分支上下文的操作数.
            // 如果已知当前正在翻译哪些块(branchBlocks 提示),
            // 选取定义指令位于这些块中的 PHI 操作数.
            // 否则选取第一个非平凡操作数.
            case PHI -> {
                Expression resolved = null;
                if (currentBranchBlocks != null) {
                    for (Value op : insn.operands()) {
                        if (op instanceof InstructionRef ref
                                && currentBranchBlocks.contains(ref.instruction().blockId())) {
                            resolved = translateExpr(ref.instruction());
                            break;
                        }
                    }
                }
                if (resolved == null) {
                    for (Value op : insn.operands()) {
                        if (op instanceof InstructionRef ref) {
                            resolved = translateExpr(ref.instruction());
                            break;
                        }
                        if (op instanceof ConstantValue(Object value, JavaType type)) {
                            resolved = new LitExpr(value, type);
                            break;
                        }
                        if (op instanceof Variable v) {
                            resolved = varToExpr(v);
                            break;
                        }
                    }
                }
                yield resolved != null ? resolved : new VarExpr("merge" + insn.id());
            }

            default -> new VarExpr("/* " + insn.opcode() + " */");
        };
    }

    /** 将 Value(Variable / ConstantValue / InstructionRef)转为 Expression.
     *  对于 InstructionRef,递归翻译引用的指令以构建正确的表达式树. */
    private Expression valueToExpr(Value v) {
        return ExpressionTranslator.valueToExpr(v, currentVarStoreSource, this::translateExpr);
    }

    /** 检测复合赋值模式:{@code x = x OP y} → {@code x OP= y}.
     *  若模式匹配则返回运算符,否则返回 null(普通赋值). */
    private BinaryOperator detectCompoundOp(Expression lhs, Expression rhs) {
        if (!(rhs instanceof BinExpr bin)) {
            return null;
        }
        // 匹配:lhs 与二元表达式的左操作数匹配
        if (expressionsMatch(lhs, bin.left())) {
            return bin.operator();
        }
        return null;
    }

    /** 检查两个表达式在结构上是否等价(相同的变量/字段).
     *  处理等价关系:{@code VarExpr("size") ≈ FieldAccessExpr(this, "size")},
     *  这是由于 {@code FIELD_LOAD on this} 发出了裸字段名. */
    private boolean expressionsMatch(Expression a, Expression b) {
        if (a instanceof VarExpr va && b instanceof VarExpr vb) {
            return va.name().equals(vb.name());
        }
        if (a instanceof FieldAccessExpr fa && b instanceof FieldAccessExpr fb) {
            return fa.fieldName().equals(fb.fieldName())
                    && (fa.target() == null && fb.target() == null
                    || (fa.target() != null && fb.target() != null
                    && expressionsMatch(fa.target(), fb.target())));
        }
        // 跨类型:VarExpr("size") 匹配 FieldAccessExpr(this, "size")
        if (a instanceof VarExpr va && b instanceof FieldAccessExpr fb) {
            return fb.target() instanceof VarExpr t && "this".equals(t.name())
                    && va.name().equals(fb.fieldName());
        }
        if (b instanceof VarExpr vb && a instanceof FieldAccessExpr fa) {
            return fa.target() instanceof VarExpr t && "this".equals(t.name())
                    && vb.name().equals(fa.fieldName());
        }
        return false;
    }

    /** 将 Variable 转为相应的 VarExpr.
     *  使用变量名(优先从 LocalVariableTable 获取,回退到 "var" + originalIndex).
     *  代表 slot-0 临时值的版本化变量与 {@code this} 进行区分. */
    private VarExpr varToExpr(Variable var) {
        return ExpressionTranslator.varToExpr(var, isInstanceMethod);
    }

    /** 将 CONST IR 转为 LitExpr */
    /** 将 CONST IR 转为 LitExpr */
    private Expression constToExpr(IrInstruction insn) {
        return ExpressionTranslator.constToExpr(insn);
    }

    /** 检查是否有局部变量与给定字段名相同,这将在剥离 "this." 前缀时造成歧义 */
    private boolean localVarShadowsField(String fieldName) {
        if (currentIr == null || fieldName == null) {
            return false;
        }
        for (Variable v : currentIr.variables()) {
            String name = v.name();
            if (name != null && name.equals(fieldName) && !(v.slot() == 0 && v.version() == 0)) {
                return true;
            }
        }
        return false;
    }

    /** 将字节码操作码映射为 UNARY IR 指令的一元运算符 */
    private UnaryOperator inferUnaryOp(int bc) {
        return switch (bc) {
            case 0x74, 0x75, 0x76, 0x77 -> UnaryOperator.NEG; // INEG, LNEG, FNEG, DNEG
            default -> UnaryOperator.NEG;
        };
    }

    /** 根据 switch 信息和分组块构建 SwitchStatement */
    private SwitchStatement buildSwitch(SwitchInfo info, BlockGroup group, LinearIr ir,
                                        List<BlockGroup> allGroups, Set<BlockGroup> consumed) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        Expression discriminant = new VarExpr("switchKey");
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.SWITCH && !insn.operands().isEmpty()) {
                discriminant = valueToExpr(insn.operands().getFirst());
                break;
            }
        }

        // 收集所有 case 目标块,以便消费它们对应的组
        Set<BasicBlock> allCaseBlocks = new HashSet<>();
        info.caseBodies().values().forEach(allCaseBlocks::addAll);
        allCaseBlocks.addAll(info.defaultBody());

        // 消费包含 case 目标块的组
        for (BlockGroup g : allGroups) {
            if (consumed.contains(g)) {
                continue;
            }
            for (BasicBlock gb : g.blocks()) {
                if (allCaseBlocks.contains(gb)) {
                    consumed.add(g);
                    break;
                }
            }
        }

        List<SwitchStatement.CaseGroup> caseGroups = new ArrayList<>();
        for (var entry : info.caseBodies().entrySet()) {
            List<Expression> labels = List.of(
                    new LitExpr(entry.getKey(), JavaType.INT));
            List<Statement> body = new ArrayList<>();
            for (BasicBlock b : entry.getValue()) {
                body.addAll(translateBlockGroup(new BlockGroup(b), ir));
            }
            caseGroups.add(new SwitchStatement.CaseGroup(labels, body, false));
        }
        if (!info.defaultBody().isEmpty()) {
            List<Statement> defBody = new ArrayList<>();
            for (BasicBlock b : info.defaultBody()) {
                defBody.addAll(translateBlockGroup(new BlockGroup(b), ir));
            }
            caseGroups.add(new SwitchStatement.CaseGroup(List.of(), defBody, true));
        }

        return new SwitchStatement(discriminant, caseGroups);
    }

    /**
     * 如果指令具有 BOOLEAN_RETURN 注解且表达式为数值 LitExpr,
     * 则将其替换为布尔 LitExpr.
     */
    private Expression applyBooleanAnnotation(IrInstruction insn, Expression expr) {
        if (!insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN)) {
            return expr;
        }
        // 如果值来自 PHI,不覆盖——分支上下文解析已经选择了正确的逐分支值
        if (!insn.operands().isEmpty()
                && insn.operands().getFirst() instanceof InstructionRef ref
                && ref.instruction().opcode() == IrOpcode.PHI) {
            return expr;
        }
        var ann = insn.getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN);
        if (ann == null) {
            return expr;
        }
        boolean boolVal = ann.getBoolean(
                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_BOOLEAN_VALUE);
        return new LitExpr(boolVal, JavaType.BOOLEAN);
    }

    /** 检查组内任意 IR 指令是否具有 SYNCHRONIZED_BLOCK 标记.
     *  该方法比匹配输出占位符文本更可靠. */
    private boolean groupHasSynchronizedAnnotation(BlockGroup group, LinearIr ir) {
        return group.allIrInstructions(ir).stream().anyMatch(
                i -> i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK)
                        && i.opcode() == IrOpcode.MONITOR_ENTER);
    }

    /** 检查语句树是否包含 synchronized 块注解.
     *  通过 IR 级别标记检测的回退方案. */
    private boolean isSynchronizedBlock(Statement stmt) {
        return false; // 检测现在使用 groupHasSynchronizedAnnotation 中的 IR 级别标记
    }

    /** 递归检查语句树中是否包含 SynchronizedStatement */
    private boolean containsSynchronizedStatement(Statement s) {
        if (s instanceof SynchronizedStatement) {
            return true;
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream().anyMatch(this::containsSynchronizedStatement);
        }
        if (s instanceof TryStatement t) {
            return containsSynchronizedStatement(t.tryBody());
        }
        return false;
    }

    /** 检查 TryCatchInfo 是否表示 synchronized 块:
     *  方法包含 MONITOR_ENTER,处理器包含 MONITOR_EXIT + THROW.
     *  MONITOR_ENTER 通常位于 try 范围之前的字节码偏移处,
     *  因此需要搜索整个方法 IR 而非仅限 try 块. */
    private boolean isSynchronizedHandler(TryCatchInfo info, LinearIr ir) {
        // 在整个方法中搜索 MONITOR_ENTER(通常位于 tryStartPc - 1 处)
        boolean hasMonitorEnter = false;
        for (BasicBlock b : ir.controlFlowGraph().blocks()) {
            if (b == ir.controlFlowGraph().entryBlock()
                    || b == ir.controlFlowGraph().exitBlock()) {
                continue;
            }
            for (IrInstruction insn : ir.instructionsOf(b)) {
                if (insn.opcode() == IrOpcode.MONITOR_ENTER) {
                    hasMonitorEnter = true;
                    break;
                }
            }
            if (hasMonitorEnter) {
                break;
            }
        }
        if (!hasMonitorEnter) {
            return false;
        }
        // 检查处理器中是否有 MONITOR_EXIT + THROW
        List<IrInstruction> handlerInsns = collectHandlerInstructions(info, ir);
        boolean hasMonitorExit = false;
        boolean hasThrow = false;
        for (IrInstruction insn : handlerInsns) {
            if (insn.opcode() == IrOpcode.MONITOR_EXIT) {
                hasMonitorExit = true;
            }
            if (insn.opcode() == IrOpcode.THROW) {
                hasThrow = true;
            }
        }
        return hasMonitorExit && hasThrow;
    }

    /**
     * 检测 TryCatchInfo 是否表示 MatchException 模式匹配处理器.
     *
     * <p>记录模式/类型模式 switch 在字节码中生成 MatchException 处理器,
     * 其形式为:NEW MatchException + INVOKE <init> + THROW.
     * 这些处理器是编译器合成的,不是真正的 Java catch 子句.
     *
     * <p>若将此类处理器包装为 try-catch,会导致 try 体中的 INVOKE 和 STORE
     * 指令被拆分到不同基本块中,产生死代码(return 后跟随未执行语句).
     * 直接跳过 try-catch 包装,使 INVOKE+STORE 保持在一起.
     */
    private boolean isMatchExceptionHandler(TryCatchInfo info, LinearIr ir) {
        List<IrInstruction> handlerInsns = collectHandlerInstructions(info, ir);
        if (handlerInsns.isEmpty()) {
            return false;
        }
        // 检查是否包含 NEW 指令(创建异常对象)
        boolean hasNew = false;
        boolean hasThrow = false;
        for (IrInstruction insn : handlerInsns) {
            if (insn.opcode() == IrOpcode.NEW) {
                // 检查创建的异常类型是否为 MatchException
                JavaType rt = insn.resultType();
                if (rt != null && rt.internalName() != null
                        && rt.internalName().contains("MatchException")) {
                    hasNew = true;
                }
            }
            if (insn.opcode() == IrOpcode.THROW) {
                hasThrow = true;
            }
        }
        return hasNew && hasThrow;
    }

    /** 从 synchronized try-catch 中提取监视器对象名称 */
    private String extractMonitorObject(TryCatchInfo info, LinearIr ir) {
        for (BasicBlock b : info.tryBlocks()) {
            for (IrInstruction insn : ir.instructionsOf(b)) {
                if (insn.opcode() == IrOpcode.MONITOR_ENTER && !insn.operands().isEmpty()) {
                    Value obj = insn.operands().getFirst();
                    // 沿 InstructionRef 链追踪以找到底层变量
                    while (obj instanceof InstructionRef ref) {
                        IrInstruction def = ref.instruction();
                        if (!def.operands().isEmpty()
                                && def.operands().getFirst() instanceof Variable v) {
                            if (v.slot() == 0) {
                                return "this";
                            }
                            return v.name();
                        }
                        if (!def.operands().isEmpty()
                                && def.operands().getFirst() instanceof InstructionRef r) {
                            obj = r; // 继续追踪
                        } else {
                            break;
                        }
                    }
                    if (obj instanceof Variable v) {
                        if (v.slot() == 0) {
                            return "this";
                        }
                        return v.name();
                    }
                }
            }
        }
        return "this";
    }

    /** 从方法体中剥离 synchronized 前导代码(DUP/ASTORE),
     *  生成干净的 {@code synchronized(expr) { body }} 输出.
     *  同时过滤掉从 monitorexit 异常处理器中泄露的处理器伪影
     *  (如 while(true){throw...}). */
    private SynchronizedStatement stripSyncPreamble(SynchronizedStatement syncStmt) {
        Statement body = syncStmt.body();
        String monObj = ((VarExpr) syncStmt.monitorObject()).name();
        if (body instanceof BlockStatement bs) {
            List<Statement> filtered = new ArrayList<>();
            for (Statement s : bs.statements()) {
                // 剥离 "Type varN = this" 前导代码(DUP+ASTRORE 模式)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                        && (vd.name().equals(monObj) || isTypicalSyncTemp(vd))) {
                    continue;
                }
                // 剥离仅为 varN 的 ExpressionStatement(未消费的加载)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.ExpressionStatement es
                        && es.expression() instanceof VarExpr v
                        && v.name().equals(monObj)) {
                    continue;
                }
                // 剥离处理器伪影:while(true){Throwable varN; throw varN;}
                // 这些是 monitorexit 异常处理器泄露到方法体中的
                if (s instanceof LoopStatement loop
                        && loop.loopKind() == LoopStatement.LoopKind.WHILE
                        && loop.condition() instanceof VarExpr vc
                        && "true".equals(vc.name())) {
                    Statement loopBody = loop.body();
                    if (loopBody instanceof BlockStatement lbs) {
                        boolean isHandlerArtifact = false;
                        for (Statement lb : lbs.statements()) {
                            if (lb instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                                    && vd.type().descriptor() != null
                                    && vd.type().descriptor().contains("Throwable")) {
                                isHandlerArtifact = true;
                            }
                        }
                        if (isHandlerArtifact) {
                            continue; // 跳过此处理器伪影
                        }
                    }
                }
                // 同时剥离 synchronized 内部的裸 ThrowStatement(处理器泄露)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.ThrowStatement) {
                    continue;
                }
                // 剥离不可达代码之后的 "return;"(synchronized 体清理)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement r
                        && r.value() == null) {
                    continue;
                }
                filtered.add(s);
            }
            if (filtered.isEmpty()) {
                return syncStmt;
            }
            if (filtered.size() == 1) {
                return new SynchronizedStatement(syncStmt.monitorObject(), filtered.getFirst());
            }
            return new SynchronizedStatement(syncStmt.monitorObject(), new BlockStatement(filtered));
        }
        return syncStmt;
    }

    /** 检查变量声明是否类似于 synchronized 临时副本 */
    private boolean isTypicalSyncTemp(com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd) {
        String name = vd.name();
        return (name.startsWith("var") && name.length() <= 5)
                || (vd.initializer() instanceof VarExpr v && "this".equals(v.name()));
    }

    /** 剥离 SynchronizedStatement 之前设置监视器对象的变量声明.
     *  这些是字节码模式 aload_0, dup, astore_1, monitorenter 的 DUP/ASTORE 伪影. */
    private BlockStatement stripSyncPreambles(BlockStatement root) {
        List<Statement> stmts = new ArrayList<>(root.statements());
        for (int i = 0; i < stmts.size() - 1; i++) {
            if (stmts.get(i + 1) instanceof SynchronizedStatement syncStmt) {
                // 剥离作为 synchronized 临时副本的 VariableDeclaration
                if (stmts.get(i) instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                        && isTypicalSyncTemp(vd)) {
                    stmts.remove(i);
                    i--; // 重新检查此索引处的新元素
                }
                // 剥离 ExpressionStatement "varN"(未消费的加载)
                if (i >= 0 && stmts.get(i) instanceof com.bingbaihanji.bdec.ast.stmt.ExpressionStatement es
                        && es.expression() instanceof VarExpr v
                        && v.name().startsWith("var")) {
                    stmts.remove(i);
                    i--;
                }
            }
        }
        if (stmts.size() == 1) {
            return stmts.getFirst() instanceof BlockStatement bs ? bs
                    : new BlockStatement(stmts);
        }
        return new BlockStatement(stmts);
    }

    /** 将语句树包装为 synchronized 块 */
    private SynchronizedStatement wrapSynchronized(Statement body,
                                                   BlockGroup group, LinearIr ir) {
        // 从 MONITOR_ENTER 注解中找到监视器对象
        String monitorObj = "obj";
        for (IrInstruction insn : group.allIrInstructions(ir)) {
            if (insn.opcode() == IrOpcode.MONITOR_ENTER
                    && insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK)) {
                var ann = insn.getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK);
                if (ann != null) {
                    String desc = ann.getString(com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_MONITOR_OBJECT);
                    if (desc != null) {
                        monitorObj = desc;
                    }
                }
                break;
            }
        }

        // 从方法体中过滤掉 monitor enter/exit 指令
        if (body instanceof BlockStatement bs) {
            List<Statement> filtered = new ArrayList<>();
            for (Statement s : bs.statements()) {
                if (s instanceof ExpressionStatement es) {
                    if (es.expression() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                            && ("/* monitor enter */".equals(v.name())
                            || "/* monitor exit */".equals(v.name()))) {
                        continue;
                    }
                }
                filtered.add(s);
            }
            body = new BlockStatement(filtered);
        }

        return new SynchronizedStatement(
                new com.bingbaihanji.bdec.ast.expr.VarExpr(monitorObj), body);
    }

    /**
     * 根据 try-catch 信息构建 TryStatement.
     *
     * <p>检测 finally 块:当处理器为 catch-all(null 或 Throwable)
     * 且以 THROW 结尾(重新抛出模式)时,提取处理器体中除去 throw 之外的部分作为 finally 块.
     */
    /** 构建一个覆盖所有处理器块的 BlockGroup,沿 fallthrough 链从初始处理器块开始追踪 */
    private BlockGroup buildHandlerBlockGroup(TryCatchInfo info, LinearIr ir) {
        BlockGroup group = new BlockGroup(info.handlerBlock());
        ControlFlowGraph cfg = ir.controlFlowGraph();
        BasicBlock current = info.handlerBlock();
        Set<BasicBlock> visited = new HashSet<>();
        visited.add(current);
        while (true) {
            // 沿单一非异常后继追踪
            BasicBlock next = null;
            for (var edge : cfg.outgoingOf(current)) {
                if (edge.kind() != EdgeKind.EXCEPTION) {
                    if (next == null) {
                        next = edge.target();
                    } else {
                        next = null; // 多个后继 → 停止
                        break;
                    }
                }
            }
            if (next == null || next == cfg.exitBlock() || !visited.add(next)) {
                break;
            }
            group.add(next);
            current = next;
        }
        return group;
    }

    /** 收集处理器的所有 IR 指令,沿 fallthrough 链追踪.
     *  当 CFG 因自引用异常边而将处理器块分割时使用此方法. */
    private List<IrInstruction> collectHandlerInstructions(TryCatchInfo info, LinearIr ir) {
        List<IrInstruction> result = new ArrayList<>();
        ControlFlowGraph cfg = ir.controlFlowGraph();
        BasicBlock current = info.handlerBlock();
        Set<BasicBlock> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            result.addAll(ir.instructionsOf(current));
            // 沿单一 fallthrough 后继(如有)继续追踪
            List<BasicBlock> succs = cfg.successorsOf(current);
            // 仅过滤非异常边
            BasicBlock next = null;
            for (var edge : cfg.outgoingOf(current)) {
                if (edge.kind() != EdgeKind.EXCEPTION) {
                    if (next == null) {
                        next = edge.target();
                    } else {
                        next = null; // 多条非异常后继 → 停止
                        break;
                    }
                }
            }
            if (next == null || next == cfg.exitBlock()) {
                break;
            }
            current = next;
        }
        return result;
    }

    /** 将处理器指令(去除最后的 THROW)翻译为 Statement 体.
     *  直接翻译收集到的处理器指令,而不依赖 BlockGroup/块分组,
     *  后者可能遗漏分割的处理器片段. */
    private Statement translateHandlerWithoutThrow(TryCatchInfo info, LinearIr ir,
                                                   List<IrInstruction> handlerInsns) {
        // 跳过 finally 体的最后一条 THROW 指令
        List<IrInstruction> bodyInsns = handlerInsns;
        if (!bodyInsns.isEmpty() && bodyInsns.getLast().opcode() == IrOpcode.THROW) {
            bodyInsns = bodyInsns.subList(0, bodyInsns.size() - 1);
        }
        if (bodyInsns.isEmpty()) {
            return new BlockStatement(List.of());
        }

        // 为这些指令构建已消费集合
        Set<Integer> consumed = new HashSet<>();
        Map<Variable, Integer> loadVarToId = new HashMap<>();
        for (IrInstruction insn : bodyInsns) {
            if (insn.opcode() == IrOpcode.LOAD && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                loadVarToId.put(v, insn.id());
            }
        }
        for (IrInstruction insn : bodyInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                } else if (op instanceof Variable v && loadVarToId.containsKey(v)) {
                    consumed.add(loadVarToId.get(v));
                }
            }
        }

        // 翻译每条作为语句根或未被消费的指令
        List<Statement> stmts = new ArrayList<>();
        for (IrInstruction insn : bodyInsns) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                continue;
            }
            if (currentStoresToSkip.contains(insn.id())) {
                continue;
            }
            if (isStatementRoot(insn)) {
                Statement s = translateStmt(insn);
                if (s != null) {
                    stmts.add(s);
                }
            } else if (!consumed.contains(insn.id()) && insn.resultValue() != null) {
                Expression e = translateExpr(insn);
                if (e != null && !isIgnorableExpr(e)) {
                    stmts.add(new ExpressionStatement(e));
                }
            }
        }
        if (stmts.isEmpty()) {
            return new BlockStatement(List.of());
        }
        if (stmts.size() == 1) {
            return stmts.getFirst();
        }
        return new BlockStatement(stmts);
    }

    private TryStatement buildTryCatch(TryCatchInfo info, Statement tryBody, LinearIr ir) {
        boolean isCatchAll = info.catchType() == null
                || "java/lang/Throwable".equals(info.catchType());

        // 沿 fallthrough 链收集所有处理器指令.
        // CFG 可能因 finally 处理器中的自引用异常边而将处理器块分割,
        // 因此 THROW 可能位于后继块中.
        List<IrInstruction> handlerInsns = collectHandlerInstructions(info, ir);

        // 检查是否为 finally 模式:catch-all + 以 THROW 结尾
        // 但若处理器创建了新的异常对象(含 NEW 指令),则是 catch 子句
        //(如 record 模式匹配的 MatchException),而非 finally
        boolean isFinally = isCatchAll && !handlerInsns.isEmpty()
                && handlerInsns.getLast().opcode() == IrOpcode.THROW
                && !containsNewInstruction(handlerInsns);

        if (isFinally) {
            // 提取 finally 体:所有处理器指令去除最后的 THROW.
            // 构建一个跨越所有处理器块的合成 BlockGroup 用于翻译.
            Statement finallyBody = translateHandlerWithoutThrow(info, ir, handlerInsns);

            // 从输出语句中过滤掉 THROW
            if (finallyBody instanceof BlockStatement bs) {
                List<Statement> stmts = new ArrayList<>();
                for (Statement s : bs.statements()) {
                    if (s instanceof ThrowStatement) {
                        continue;
                    }
                    if (s instanceof ExpressionStatement es
                            && es.expression() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                            && "/* throw */".equals(v.name())) {
                        continue;
                    }
                    stmts.add(s);
                }
                finallyBody = new BlockStatement(stmts);
            }

            // 从 try 体中剥离重复的 finally 体语句.
            // 字节码会重复 finally 代码:一次在正常退出路径中
            //(被分组到 try 体中),一次在处理器中.
            // 我们希望 finally 代码仅出现在 finally 块中.
            tryBody = stripDuplicatedFinally(tryBody, finallyBody);

            return new TryStatement(tryBody, List.of(), finallyBody);
        }

        // 常规 catch 子句
        List<TryStatement.CatchClause> catchClauses = new ArrayList<>();
        String excType = info.catchType();
        if (excType != null && excType.contains("/")) {
            excType = excType.substring(excType.lastIndexOf('/') + 1);
        }
        // 若处理器创建了新的异常对象(如 record 模式匹配的 MatchException),
        // 则这是编译器生成的基础设施,而非用户代码.
        // 此时生成最小化的空 catch 体以保持代码可编译,
        // 而非尝试翻译包含无作用域变量的原始处理器指令.
        Statement handlerBody;
        if (containsNewInstruction(handlerInsns)) {
            // 编译器生成的 record 模式匹配处理器——用简单的 throw e 保持可编译
            handlerBody = new BlockStatement(List.of(
                    new com.bingbaihanji.bdec.ast.stmt.ThrowStatement(
                            new VarExpr("e"))));
        } else {
            // 用户编写的 catch 子句——正常翻译处理器指令
            BlockGroup handlerGroup = new BlockGroup(info.handlerBlock());
            handlerBody = translateGroup(handlerGroup, ir);
            if (handlerBody == null) {
                handlerBody = new BlockStatement(List.of());
            }
        }
        catchClauses.add(new TryStatement.CatchClause(
                excType != null ? excType : "Exception",
                "e",
                handlerBody));
        return new TryStatement(tryBody, catchClauses, null);
    }

    /**
     * 从 try 体中剥离同样出现在 finally 体中的语句.
     * 基于 Expression 对象的结构化比较而非 toString().
     * 递归处理嵌套的复合语句(IfStatement,LoopStatement 等),
     * 使 if-else 分支内的重复 finally 代码也被剥离.
     */
    private Statement stripDuplicatedFinally(Statement tryBody, Statement finallyBody) {
        List<Statement> finallyStmts = collectStatements(finallyBody);
        if (finallyStmts.isEmpty()) {
            return tryBody;
        }
        return stripMatchingFinally(tryBody, finallyStmts);
    }

    /** 递归地从复合语句中剥离与模式(finally 体语句)匹配的语句,
     *  而非仅处理顶层. */
    private Statement stripMatchingFinally(Statement s, List<Statement> patterns) {
        if (s == null) {
            return null;
        }
        // 如果此语句自身与某模式匹配,则完全移除
        if (matchesAny(s, patterns)) {
            return null;
        }
        // 递归处理 BlockStatement
        switch (s) {
            case BlockStatement bs -> {
                List<Statement> filtered = new ArrayList<>();
                for (Statement child : bs.statements()) {
                    Statement stripped = stripMatchingFinally(child, patterns);
                    if (stripped != null) {
                        filtered.add(stripped);
                    }
                }
                if (filtered.isEmpty()) {
                    return new BlockStatement(List.of());
                }
                if (filtered.size() == 1) {
                    return filtered.getFirst();
                }
                return new BlockStatement(filtered);
            }

            // 递归处理 IfStatement 的分支
            case IfStatement i -> {
                Statement thenStripped = stripMatchingFinally(i.thenBranch(), patterns);
                Statement elseStripped = i.elseBranch() != null
                        ? stripMatchingFinally(i.elseBranch(), patterns) : null;
                return new IfStatement(i.condition(),
                        thenStripped != null ? thenStripped : new BlockStatement(List.of()),
                        elseStripped);
            }

            // 递归处理 LoopStatement 的方法体
            case LoopStatement l -> {
                Statement bodyStripped = stripMatchingFinally(l.body(), patterns);
                return new LoopStatement(
                        l.loopKind(), l.condition(),
                        bodyStripped != null ? bodyStripped : new BlockStatement(List.of()));
            }

            // 递归处理 TryStatement 的 try 体和 finally 体
            case TryStatement t -> {
                Statement tryStripped = stripMatchingFinally(t.tryBody(), patterns);
                List<TryStatement.CatchClause> cc = new ArrayList<>();
                for (var c : t.catchClauses()) {
                    Statement bodyStripped = stripMatchingFinally(c.body(), patterns);
                    cc.add(new TryStatement.CatchClause(
                            c.exceptionType(), c.varName(),
                            bodyStripped != null ? bodyStripped : new BlockStatement(List.of())));
                }
                Statement finallyStripped = t.finallyBody() != null
                        ? stripMatchingFinally(t.finallyBody(), patterns) : null;
                return new TryStatement(
                        tryStripped != null ? tryStripped : new BlockStatement(List.of()),
                        cc, finallyStripped);
            }
            default -> {
            }
        }
        return s;
    }

    /** 将单个基本块组翻译为语句列表 */
    private List<Statement> translateBlockGroup(BlockGroup group, LinearIr ir) {
        Statement s = translateGroup(group, ir);
        if (s instanceof BlockStatement bs) {
            return bs.statements();
        }
        // 单条语句(ReturnStatement 等)——translateGroup
        // 在仅有一条语句时会解开 BlockStatement 包装.
        if (s != null) {
            return List.of(s);
        }
        return List.of();
    }

    /**
     * 为单次使用的变量构建全局(跨组)的 Variable → 存储值的内联映射.
     * 这使得常量可以跨组边界内联,例如 STORE 位于 try 体组中而
     * LOAD+RETURN 位于正常退出组中(常见于 try-finally 模式).
     */
    private void buildGlobalVarInlineMap(List<BlockGroup> groups, LinearIr ir) {
        Map<Variable, Value> varStoreSource = new HashMap<>();
        Set<Integer> storesToSkip = new HashSet<>();

        // 跨所有组收集全部指令
        List<IrInstruction> allInsns = new ArrayList<>();
        for (BlockGroup g : groups) {
            allInsns.addAll(g.allIrInstructions(ir));
        }

        // 第一遍:统计每个 Variable 被引用的次数
        //(同时通过 LOAD 和其他指令操作数如 RETURN 进行统计).
        Map<Variable, Integer> varUseCount = new HashMap<>();
        Map<Variable, Integer> loadIdForVar = new HashMap<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.LOAD && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                varUseCount.merge(v, 1, Integer::sum);
                loadIdForVar.put(v, insn.id());
            }
            // 同时统计直接的 Variable 引用(例如 RETURN 操作数)
            for (Value op : insn.operands()) {
                if (op instanceof Variable v && insn.opcode() != IrOpcode.STORE
                        && insn.opcode() != IrOpcode.LOAD) {
                    varUseCount.merge(v, 1, Integer::sum);
                }
            }
        }

        // 构建已消费集合(InstructionRef 使用 + 直接 Variable 使用)
        Set<Integer> consumedInsnIds = new HashSet<>();
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumedInsnIds.add(ref.instruction().id());
                }
            }
        }

        // 第二遍:追踪单次使用变量的存储
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                    && insn.operands().get(0) instanceof Variable v) {
                Value source = insn.operands().get(1);
                int useCount = varUseCount.getOrDefault(v, 0);
                if (useCount == 1 && isSimpleValue(source)) {
                    // 检查 LOAD 是否被消费,或变量是否被直接使用(如 RETURN 操作数)
                    Integer loadId = loadIdForVar.get(v);
                    boolean canInline;
                    if (loadId != null) {
                        // 变量通过 LOAD 加载——检查 LOAD 是否被消费
                        canInline = consumedInsnIds.contains(loadId);
                    } else {
                        // 变量被直接使用——始终安全内联(变量自身即为使用点)
                        canInline = true;
                    }
                    if (canInline) {
                        varStoreSource.put(v, source);
                        storesToSkip.add(insn.id());
                    }
                }
            }
        }

        currentVarStoreSource = Map.copyOf(varStoreSource);
        currentStoresToSkip = Set.copyOf(storesToSkip);
        globalVarUseCount = Map.copyOf(varUseCount);
    }

    /**
     * 全局预遍历:合并跨组的 NEW + INVOKE {@code <init>} 对.
     *
     * <p>对标 CFR 的 CondenseConstruction 和 Vineflower 的
     * {@code SimplifyExprentsHelper.isSimpleConstructorInvocation()}.
     * 当 NEW 指令和对应的 CONSTRUCTOR_DELEGATION INVOKE 被拆分到
     * 不同的 BlockGroup 中时(例如记录,sealed 类构造),执行合并.
     * 如果仅做组内合并,会产生:
     * <pre>{@code
     *   RecordDemo("Alice", 25);  // 孤立的构造函数调用
     *   RecordDemo r = new RecordDemo(); // 无参 new
     * }</pre>
     * 而不是正确的:
     * <pre>{@code RecordDemo r = new RecordDemo("Alice", 25);}</pre>
     */
    private void buildGlobalNewInitMergeMap(List<BlockGroup> groups, LinearIr ir) {
        // 收集所有组中的所有指令,并计算跨组 consumed 集合
        Set<Integer> allConsumed = new HashSet<>();
        List<IrInstruction> allInsns = new ArrayList<>();
        for (BlockGroup group : groups) {
            List<IrInstruction> groupInsns = group.allIrInstructions(ir);
            allInsns.addAll(groupInsns);
            for (IrInstruction insn : groupInsns) {
                for (Value op : insn.operands()) {
                    if (op instanceof InstructionRef ref) {
                        allConsumed.add(ref.instruction().id());
                    }
                }
            }
        }

        // 合并所有 CONSTRUCTOR_DELEGATION INVOKE(非 this/super)到其对应的 NEW 指令中
        Map<Integer, List<IrInstruction>> newToInit = new HashMap<>();
        Set<Integer> initToSkip = new HashSet<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.INVOKE
                    && insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION)
                    && !insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                    && !insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)) {
                for (Value op : insn.operands()) {
                    if (op instanceof InstructionRef ref) {
                        IrInstruction def = ref.instruction();
                        if (def.opcode() == IrOpcode.NEW && allConsumed.contains(def.id())) {
                            newToInit.computeIfAbsent(def.id(), k -> new ArrayList<>()).add(insn);
                            initToSkip.add(insn.id());
                            break;
                        }
                    }
                }
            }
        }
        currentNewToInit = newToInit;
        currentInitToSkip = initToSkip;
    }

    // ── BlockGroup 内部辅助类 ─────────────────────────────────────────────

    /**
     * 块组,将相邻的连续基本块聚合成一个逻辑组.
     * 用于将多个紧密相关的基本块一起处理为一条复合语句.
     */
    private static class BlockGroup {

        /** 组内的基本块列表 */
        private final List<BasicBlock> blocks = new ArrayList<>();

        BlockGroup(BasicBlock first) {blocks.add(first);}

        void add(BasicBlock b) {blocks.add(b);}

        BasicBlock first() {return blocks.getFirst();}

        BasicBlock last() {return blocks.getLast();}

        List<BasicBlock> blocks() {return blocks;}

        /** 收集组内所有基本块的全部 IR 指令 */
        List<IrInstruction> allIrInstructions(LinearIr ir) {
            List<IrInstruction> result = new ArrayList<>();
            for (BasicBlock b : blocks) {
                result.addAll(ir.instructionsOf(b));
            }
            return result;
        }
    }
}
