package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.AnnotationRenderer;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.bytecode.model.TypePathElement;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
 *
 * <p><b>职责拆分</b>(避免上帝类,参考 Vineflower 每模式一处理器的做法):
 * <ul>
 *   <li>{@link StatementUtils} — 无状态的语句/表达式形状判断与纯变换
 *       (后置自增折叠,return 包装,语句文本化等);</li>
 *   <li>{@link AstCleanup} — 无状态的 AST 后处理(同步前导剥离,
 *       catch 泄漏清理,finally 去重,case 终结判断,条件简化);</li>
 *   <li>{@link SwitchTranslator} — switch 模式翻译(case 区域结构化,
 *       switch 表达式结果识别,typeSwitch 守卫),经 {@link ReducerOps} 回调;</li>
 *   <li>{@link LoopTranslator} — 循环模式翻译(循环体结构化 continue/break,
 *       do-while/while 包装与条件引用声明提升),经 {@link ReducerOps} 回调;</li>
 *   <li>本类实现 {@link ReducerOps},仅保留依赖归约状态的编排逻辑:
 *       块分组翻译,if/try 结构化,IR→表达式树的语境化解析.</li>
 * </ul>
 */
public final class BlockReducer implements ReducerOps {


    /** 当前方法是否为实例方法 */
    private final boolean isInstanceMethod;

    /** 已声明变量名的作用域栈.进入/离开分支体时压入/弹出,
     *  确保每个分支拥有独立的临时变量声明. */
    private final Deque<Set<String>> declaredVarNameStack = new ArrayDeque<>();

    /** INDY 指令翻译器,将 invokedynamic 模式转换为 LambdaExpr */
    private final IndyTranslator indyTranslator;

    /** NEW+INIT 合并的临时状态(CondenseConstruction 模式) */
    private Map<Integer, List<IrInstruction>> currentNewToInit = Map.of();

    /** 已合并到 NEW 中,需要跳过的 INIT 指令 ID 集合 */
    private Set<Integer> currentInitToSkip = Set.of();

    /** STORE→Variable→LOAD 链的临时内联状态 */
    private Map<Variable, Value> currentVarStoreSource = Map.of();

    /** 多次引用的 NEW_ARRAY 指令 ID → 临时变量名 */
    private Map<Integer, String> currentMultiRefArrayVar = Map.of();

    /** 当前 reduce() 调用的 switch 注解映射(供分支体翻译使用) */
    private Map<BasicBlock, SwitchInfo> currentSwitchAnns = Map.of();

    /** 当前 reduce() 调用的 loop 注解映射(供分支体翻译使用) */
    private Map<BasicBlock, LoopInfo> currentLoopAnns = Map.of();

    /** 当前 reduce() 调用的 try-catch 注解映射(供分支体翻译使用) */
    private List<TryCatchInfo> currentTryCatchAnns = List.of();

    /** 已被内联,需要跳过的 STORE 指令 ID 集合 */
    private Set<Integer> currentStoresToSkip = Set.of();

    /** PHI 指令 ID → 已折叠的三元表达式(条件赋值折叠后,后续 STORE 翻译时
     *  按此映射替换 PHI 解析,避免丢 false 分支值). */
    private Map<Integer, Expression> phiReplacements = Map.of();

    /** 待跳过的指令 ID 集合(switch 表达式 follow 的 STORE/RETURN←PHI,
     *  由 SwitchTranslator 注册——case 体已把 PHI 值解析进各 case). */
    private Set<Integer> currentSkipInsns = Set.of();

    /** 待向前折叠的后置自增语句(自增在引用语句之前的情况) */
    private Statement pendingPostInc = null;

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

    public BlockReducer() {this(true);}

    public BlockReducer(boolean isInstanceMethod) {
        this.isInstanceMethod = isInstanceMethod;
        this.indyTranslator = new IndyTranslator(
                this::getIndyAnnotation, this::valueToExpr);
    }

    /**
     * 渲染 Variable 携带的 JSR-308 类型注解(0x40 局部变量),
     * 按类型路径分组为渲染行映射.
     * 注解类型名取简单名(与声明点同包/已导入假设一致).
     */
    private static java.util.Map<java.util.List<TypePathElement>,
            List<String>> renderVarTypeAnnotations(Variable v) {
        if (v.typeAnnotations() == null || v.typeAnnotations().isEmpty()) {
            return java.util.Map.of();
        }
        return AnnotationRenderer.groupByTypePath(v.typeAnnotations());
    }

    /** 记录归约语句与所属组. */
    private static void addStatement(List<Statement> statements,
                                     List<Integer> stmtGroupIdx,
                                     Statement s, int gi) {
        statements.add(s);
        stmtGroupIdx.add(gi);
    }

    /** 表达式是否产生 boolean 值(用于 int 变量声明重标). */
    private static boolean isBooleanExpression(Expression e) {
        if (e == null) {
            return false;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.BinExpr bin) {
            BinaryOperator op = bin.operator();
            return op == BinaryOperator.AND || op == BinaryOperator.OR
                    || op == BinaryOperator.EQ || op == BinaryOperator.NE
                    || op == BinaryOperator.LT || op == BinaryOperator.GT
                    || op == BinaryOperator.LE || op == BinaryOperator.GE;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.UnExpr ue) {
            return ue.operator() == com.bingbaihanji.bdec.ast.expr.UnaryOperator.NOT;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit) {
            return lit.value() instanceof Boolean;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            return inv.returnType() != null
                    && inv.returnType().kind() == TypeKind.BOOLEAN;
        }
        // 注意:不含 VarExpr——引用 int 变量的复制会误标为 boolean.
        return false;
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
                                 List<TryCatchInfo> tryCatchAnns) {
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
        this.currentSwitchAnns = switchAnns;
        this.currentLoopAnns = loopAnns;
        this.currentTryCatchAnns = tryCatchAnns;
        this.declaredVarNameStack.clear();
        this.declaredVarNameStack.push(new HashSet<>()); // 顶层作用域

        // 预先计算后支配树,用于在 BranchAnalyzer 注解缺失时
        // 作为 if-header 检测的回退方案,以提供正确的合并点.
        PostDominatorTree postDom = PostDominatorTree.compute(graph);

        List<BlockGroup> groups = BlockGrouper.groupAdjacentBlocks(sorted, graph, loopAnns);
        Set<BlockGroup> consumed = new HashSet<>();
        // 收集处理器块,以便跳过它们对应的组(这些组将被
        // wrapTryCatchBlocks 吸收到 try-finally 中).
        Set<BasicBlock> handlerBlocks = new HashSet<>();
        for (TryCatchInfo tci : tryCatchAnns) {
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
                        // 仅收集完全由处理器路径支配的后继块.
                        // 若后继还有其他非处理器前驱(如 try 的正常路径),
                        // 它是共享的 follow 块,不是处理器片段.
                        BasicBlock succ = edge.target();
                        boolean allPredsAreHandlers = true;
                        for (var predEdge : graph.incomingOf(succ)) {
                            BasicBlock pred = predEdge.source();
                            if (!handlerBlocks.contains(pred)
                                    && !visited.contains(pred)) {
                                allPredsAreHandlers = false;
                                break;
                            }
                        }
                        if (allPredsAreHandlers) {
                            queue.add(succ);
                        }
                    }
                }
            }
        }
        // 拆分混入了处理器块和非处理器块的组.
        // 当 catch 处理器通过 FALL_THROUGH 连接到后续代码时,
        // isAdjacent 会将它们合并到同一组,导致处理器体语句在
        // try-catch 之外重复出现.拆分后处理器组会被跳过(大小=1且是处理器),
        // 而非处理器组正常处理.
        groups = BlockGrouper.splitMixedHandlerGroups(groups, handlerBlocks);

        List<Statement> statements = new ArrayList<>();
        // 每条语句对应的组索引,供 wrapTryStatements 将 try 组区间映射到语句区间
        List<Integer> stmtGroupIdx = new ArrayList<>();

        // ── 全局变量内联预遍历 ─────────────────────────
        // 扫描所有组以找出恰好被使用一次的变量的 STORE→Variable→LOAD 链.
        // 该操作跨组边界工作(对 try-finally 模式至关重要:STORE 位于 try 体组,
        // 而 LOAD+RETURN 位于正常退出组).
        InlineAnalyzer.InlineAnalysis inline = InlineAnalyzer.analyze(groups, ir);
        currentVarStoreSource = inline.varStoreSource();
        currentStoresToSkip = inline.storesToSkip();
        globalVarUseCount = inline.varUseCount();
        phiReplacements = new HashMap<>();

        // 全局预遍历:跨组合并 NEW + INVOKE <init> 对(CondenseConstruction).
        // 某些情况下 NEW 指令位于一个 BlockGroup 而对应的 <init> 调用
        // 位于另一个 BlockGroup 中(例如 record 构造, sealed 类构造等).
        ConstructionMerger.MergeResult merged = ConstructionMerger.merge(groups, ir);
        currentNewToInit = merged.newToInit();
        currentInitToSkip = merged.initToSkip();

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
            SwitchInfo switchInfo = findSwitchAnnotation(group, switchAnns);

            // 回退方案:若无 IfInfo 注解,则尝试直接从 CFG 结构检测 if-header
            //(具有 2 个后继的条件块).使用后支配树计算正确的合并点.
            if (ifInfo == null) {
                ifInfo = IfTranslator.detectIfHeader(group, graph, ir, postDom);
            }

            // 当同时存在多种注解时,优先处理 switch.
            // 模式匹配 switch(typeSwitch) 会在 switch 头块中产生 CONDITION
            //(用于 when 守卫)和回边(用于 restart 循环).
            // switch 覆盖 if-else 和 loop 注解.
            if (switchInfo != null) {
                if (ifInfo != null) {
                    ifInfo = null;
                }
                if (loopInfo != null) {
                    loopInfo = null;
                }
            } else if (loopInfo != null) {
                // 循环头块通常也是条件块(while 风格测试在顶部),
                // 未折叠循环(体内含 continue/break)会同时携带 IfInfo——
                // 循环注解优先,否则循环会被当作 if 吞噬体内代码.
                ifInfo = null;
            }

            Statement s;

            // if-else:构建包含 then 和 else 体的完整 IfStatement
            //(翻译逻辑见 IfTranslator.translateIf).
            if (ifInfo != null) {
                s = IfTranslator.translateIf(this, ifInfo, group, ir, groups, consumed, graph, postDom);
                if (s != null) {
                    addStatement(statements, stmtGroupIdx, s, gi);
                }
                continue;
            }
            // 循环:将组包装为 LoopStatement(仅在存在有效循环体时)
            else if (loopInfo != null) {
                // typeSwitch 重启循环:循环体内包含 switch 块时,
                // 不包装为 Java 循环——这是模式匹配 switch 的重试机制.
                if (loopBodyContainsSwitch(loopInfo, switchAnns)) {
                    s = translateGroup(group, ir);
                } else if (group.blocks().size() == 1
                        && group.first() == loopInfo.header()) {
                    // 未折叠的循环(体内含 continue/break 内部分支):
                    // 折叠会扁平化体内分支,因此由结构化翻译递归处理循环体.
                    Statement body = LoopTranslator.translateLoopBodyStructured(this, loopInfo, groups, ir,
                            consumed, graph, postDom);
                    if (body != null && !StatementUtils.isEmptyBlock(body)) {
                        s = LoopTranslator.wrapLoopStatement(this, loopInfo, body, extractCondition(group, ir));
                    } else {
                        // 空循环体:不可归约分裂后头块含体(do-while 形态,如
                        // D: r++; if(x>0) → C' 回边,D 既含体又含条件).用头的
                        // 非条件语句作循环体,按 do-while 包装——否则循环被
                        // 退化为普通顺序语句,静默丢失循环语义.
                        List<Statement> headerBody = translateHeaderNonCondition(group, ir);
                        if (!headerBody.isEmpty()) {
                            Expression cond = AstCleanup.simplifyCondition(
                                    extractCondition(group, ir));
                            s = new LoopStatement(LoopStatement.LoopKind.DO_WHILE,
                                    cond != null ? cond : new VarExpr("true"),
                                    StatementUtils.blockOf(headerBody));
                        } else {
                            s = translateGroup(group, ir);
                        }
                    }
                } else {
                    // 仅处理器的"循环"(来自自引用异常边):
                    // 翻译时不包装,以便 stripDuplicatedFinally 可以后续将其吸收到 finally 块中.
                    boolean isHandlerLoop = handlerBlocks.containsAll(group.blocks());
                    s = translateGroup(group, ir);
                    if (s != null && !StatementUtils.isEmptyBlock(s) && !isHandlerLoop) {
                        s = LoopTranslator.wrapLoopStatement(this, loopInfo, s, extractCondition(group, ir));
                    }
                }
            }
            // switch
            else if (switchInfo != null) {
                s = SwitchTranslator.buildSwitch(this, switchInfo, group, ir, groups, consumed, graph);
            }
            // synchronized:优先级高于 try-catch.
            // synchronized 块的异常处理器(monitorexit + athrow)是 JVM 实现细节——
            // 必须在输出中隐藏.
            else if (SynchronizedTranslator.groupHasSynchronizedAnnotation(group, ir)) {
                Statement st = translateGroup(group, ir);
                Statement syncBody = SynchronizedTranslator.collectSyncBody(this, group, groups, consumed, ir, graph);
                List<Statement> full = new ArrayList<>();
                if (st != null) {
                    full.add(st);
                }
                if (syncBody != null) {
                    full.add(syncBody);
                }
                s = SynchronizedTranslator.wrapSynchronized(StatementUtils.blockOf(full), group, ir);
            }
            // 仅处理器的组(纯异常处理器)将被 wrapTryCatchBlocks 吸收到
            // try-finally 中——跳过它们以避免死代码.
            else if (group.blocks().size() == 1 && handlerBlocks.contains(group.first())) {
                continue; // 跳过处理器块——由 try-finally 吸收
            }
            // synchronized 处理器块(monitorexit + throw)是 JVM 伪影,
            // 不应出现在输出中.
            else if (SynchronizedTranslator.isSyncHandlerGroup(group, ir)) {
                continue;
            }
            // 普通顺序块
            else {
                s = translateGroup(group, ir);
            }

            if (s != null) {
                addStatement(statements, stmtGroupIdx, s, gi);
            }
        }
        // 后处理:将孤立的 ExpressionStatement 转换为 ReturnStatement.
        // 当非 void 表达式成为孤立(未被消费)时,将其包装为 return.
        // 跳过 void 表达式(例如孤立的 lock.unlock() 调用).
        if (!statements.isEmpty() && ir.method().returnType() != null
                && ir.method().returnType().kind() != TypeKind.VOID) {
            // 方法已有显式 return 时,裸表达式语句是被丢弃的调用
            //(如 sb.append(...) 链式结果被 pop),不是应转 return 的孤立值——
            // 否则 try 体/尾部的 StringBuilder 调用会被误转成 return,
            // 返回类型错且 try-catch 内容丢失(GenericCast/EnumSwitch).
            boolean hasExplicitReturn = statements.stream()
                    .anyMatch(s -> s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement);
            if (!hasExplicitReturn) {
                for (int i = statements.size() - 1; i >= 0; i--) {
                    Statement s = statements.get(i);
                    if (s instanceof ExpressionStatement es
                            && es.expression() != null
                            && !StatementUtils.isIgnorableExpr(es.expression())
                            && !StatementUtils.isVoidExpr(es.expression())
                            && !StatementUtils.isAssignExpr(es.expression())) {
                        statements.set(i, new ReturnStatement(es.expression()));
                        break;
                    }
                    if (s instanceof BlockStatement bs && !bs.statements().isEmpty()) {
                        Statement last = bs.statements().get(bs.statements().size() - 1);
                        if (last instanceof ExpressionStatement es
                                && es.expression() != null
                                && !StatementUtils.isIgnorableExpr(es.expression())
                                && !StatementUtils.isVoidExpr(es.expression())
                                && !StatementUtils.isAssignExpr(es.expression())) {
                            List<Statement> newStmts = new ArrayList<>(bs.statements());
                            newStmts.set(newStmts.size() - 1,
                                    new ReturnStatement(es.expression()));
                            statements.set(i, new BlockStatement(newStmts));
                            // 不 break——前面可能还有需要包装的孤立表达式
                        }
                    }
                    // 跳过已处理的 ReturnStatement 或空块,继续向前扫描
                    if (s instanceof ReturnStatement || s instanceof BlockStatement) {
                        continue;
                    }
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

        // 有 try 区域时不可展开单 BlockStatement:stmtGroupIdx 只映射了外层块,
        // 展开后内部语句与组索引错位,多语句 try 体只包第一句(MultiCatch 缺陷).
        // 无 try 区域时避免双重包装.
        BlockStatement root;
        if (tryCatchAnns.isEmpty() && statements.size() == 1
                && statements.getFirst() instanceof BlockStatement bs) {
            root = bs;
        } else {
            root = new BlockStatement(statements);
        }
        root = TryTranslator.wrapTryCatchBlocks(this, root, stmtGroupIdx, groups, tryCatchAnns, ir);
        // 后处理:移除泄漏到 try-catch 外部的重复 catch 体语句
        root = AstCleanup.stripLeakedCatchStmts(root);
        // 后处理:剥离 synchronized 前导变量(DUP/ASTORE 伪影,
        // 这些是 SynchronizedStatement 之前设置监视器对象的代码)
        root = AstCleanup.stripSyncPreambles(root);
        return root;
    }

    /** 检查循环体内是否包含 switch 块(typeSwitch 重启循环). */
    private boolean loopBodyContainsSwitch(LoopInfo loop, Map<BasicBlock, SwitchInfo> switchAnns) {
        for (BasicBlock b : loop.body()) {
            if (b.containsSwitch()) {
                return true;
            }
            if (switchAnns.containsKey(b)) {
                return true;
            }
        }
        return false;
    }

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

    private SwitchInfo findSwitchAnnotation(BlockGroup group, Map<BasicBlock, SwitchInfo> switchAnns) {
        for (BasicBlock b : group.blocks()) {
            if (switchAnns.containsKey(b)) {
                return switchAnns.get(b);
            }
        }
        return null;
    }

    /**
     * 渲染指令偏移量目标(0x43 instanceof / 0x44 new / 0x47 cast)
     * 的 JSR-308 类型注解,按类型路径分组为渲染行映射.
     * target_info 为 [offset],与字节码偏移对齐.
     */
    private java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
            List<String>> renderOffsetTypeAnnotations(int targetType, int offset) {
        if (currentIr == null || currentIr.method() == null) {
            return java.util.Map.of();
        }
        var entries = currentIr.method().lookupOffsetTypeAnnotations(
                targetType, offset);
        if (entries.isEmpty()) {
            return java.util.Map.of();
        }
        return com.bingbaihanji.bdec.ast.AnnotationRenderer.groupByTypePath(entries);
    }

    /**
     * NEW_ARRAY 表达式起始偏移:指令与其操作数树的最小 sourceOffset.
     *
     * <p>javac 对数组创建的 NEW 注解偏移指向表达式起始而非 newarray 指令
     * (如 {@code new @A int[n]} 的 NEW 注解 offset=iload 的偏移,newarray 在其后).
     * 对象创建则指向 new 指令本身,不受影响.</p>
     */
    private int arrayExprStartOffset(IrInstruction insn) {
        int min = insn.sourceOffset();
        for (Value op : insn.operands()) {
            if (op instanceof InstructionRef ref) {
                min = Math.min(min, arrayExprStartOffset(ref.instruction()));
            }
        }
        return min;
    }

    /** 检查变量名是否已在当前作用域(或任何父作用域)中声明,
     *  并标记为已声明.
     *
     *  <p>Java 作用域规则(JLS 6.4):局部变量不可遮蔽外层作用域的同名变量.
     *  因此必须检查整个作用域栈——仅检查栈顶会把外层已声明的变量
     *  在分支/循环体内重复声明为 "Type name = value;"(如 while 体内的
     *  {@code int i = 0;} 与外层声明冲突).兄弟分支的独立声明不受影响:
     *  分支作用域在翻译后弹出,彼此不可见.</p> */
    public boolean tryDeclareVar(String name) {
        for (Set<String> scope : declaredVarNameStack) {
            if (scope != null && scope.contains(name)) {
                return false;
            }
        }
        Set<String> currentScope = declaredVarNameStack.peek();
        if (currentScope == null) {
            return false;
        }
        return currentScope.add(name);
    }

    /** 按支配树先序遍历排序基本块.
     *  子节点按字节码起始偏移排序(而非 HashMap 的任意顺序),
     *  确保语句按字节码顺序输出——例如 try 体之后紧跟其
     *  正常退出块(finally 副本),而非被其他子树穿插. */
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
            // 按字节码偏移排序子节点,逆序压栈使偏移最小的最先处理
            List<BasicBlock> children = new ArrayList<>(dom.children(b));
            children.sort(java.util.Comparator.comparingInt(BasicBlock::startOffset));
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
    public List<Statement> translateHeaderNonCondition(BlockGroup group, LinearIr ir) {
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
                // 带 JSR-308 类型注解的变量不可内联——内联会连同注解
                // 一起丢弃(如 @A String x = "hi" 被折叠成 println("hi"))
                if (v.typeAnnotations() != null && !v.typeAnnotations().isEmpty()) {
                    continue;
                }
                Value source = insn.operands().get(1);
                // 跳过合成异常占位符的 STORE(不参与内联)
                if (source instanceof Variable sv && sv.slot() < 0) {
                    continue;
                }
                int loadCount = 0;
                for (IrInstruction other : allInsns) {
                    if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                            && other.operands().getFirst() instanceof Variable lv
                            && lv.slot() == v.slot() && lv.version() == v.version()) {
                        loadCount++;
                    }
                }
                if (loadCount == 1 && StatementUtils.isSimpleValue(source)
                        && globalVarUseCount.getOrDefault(v, 0) == 1
                        && !InlineAnalyzer.isIncRead(allInsns, v)) {
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
                if (StatementUtils.isStatementRoot(insn)) {
                    Statement s = translateStmt(insn);
                    if (s != null) {
                        stmts.add(s);
                    }
                } else if (!consumed.contains(insn.id()) && insn.resultValue() != null) {
                    Expression e = translateExpr(insn);
                    if (e != null && !StatementUtils.isIgnorableExpr(e)) {
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
    public Expression extractCondition(BlockGroup group, LinearIr ir) {
        List<IrInstruction> all = group.allIrInstructions(ir);
        for (IrInstruction insn : all) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                return translateExpr(insn);
            }
        }
        return null;
    }

    /** 从 IfInfo 头部块直接提取条件(处理 CFG 块与 IR 块编号不匹配). */
    public Expression extractConditionFromHeader(BasicBlock header, LinearIr ir) {
        for (IrInstruction insn : ir.instructionsOf(header)) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                return translateExpr(insn);
            }
        }
        return null;
    }

    /** 扫描所有组和全部 IR 指令以查找最靠前的 CONDITION.
     *  组内查找和全局 IR 扫描一起执行,确保找到最早的条件指令. */
    @Override
    public Expression extractConditionFromAllGroups(List<BlockGroup> groups, LinearIr ir) {
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

    /**
     * 将块组翻译为语句树.
     *
     * <p>仅输出有副作用的指令(语句).中间值指令(LOAD,BINARY 等)被跳过——
     * 它们通过递归的 {@link #valueToExpr} 解析参与表达式树的构建.
     */
    public Statement translateGroup(BlockGroup group, LinearIr ir) {
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
                // 带 JSR-308 类型注解的变量不可内联——内联会连同注解
                // 一起丢弃(如 @A String x = "hi" 被折叠成 println("hi"))
                if (v.typeAnnotations() != null && !v.typeAnnotations().isEmpty()) {
                    continue;
                }
                Value source = insn.operands().get(1);
                // 跳过合成异常占位符的 STORE(不参与内联)
                if (source instanceof Variable sv && sv.slot() < 0) {
                    continue;
                }
                // 统计此组的 STORE 结果变量被加载的次数
                int loadCount = 0;
                for (IrInstruction other : allInsns) {
                    if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                            && other.operands().getFirst() instanceof Variable lv
                            && lv.slot() == v.slot() && lv.version() == v.version()) {
                        loadCount++;
                    }
                }
                if (loadCount == 1 && StatementUtils.isSimpleValue(source)
                        && globalVarUseCount.getOrDefault(v, 0) == 1
                        && !InlineAnalyzer.isIncRead(allInsns, v)) {
                    // 检查唯一的 LOAD 是否被消费
                    for (IrInstruction other : allInsns) {
                        if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                                && other.operands().getFirst() instanceof Variable lv
                                && lv.slot() == v.slot() && lv.version() == v.version()) {
                            if (consumed.contains(other.id())) {
                                if (!InlineAnalyzer.storeDominatesLoad(insn, v, other.id(), ir)) {
                                    break; // 合并点:不内联
                                }
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

        // 预扫描:为多次引用的 NEW_ARRAY 创建临时变量.
        // 若 NEW_ARRAY 被多个 ARRAY_STORE 引用(如 DUP+NEW_ARRAY+多次AASTORE),
        // 内联为独立 new 表达式会产生语义错误的代码
        //((new String[3])[0]=x; (new String[3])[1]=y 每次创建新数组).
        Map<Integer, String> multiRefArrayVar = new HashMap<>();
        Map<Integer, Integer> newArrayRefCount = new HashMap<>();
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref
                        && ref.instruction().opcode() == IrOpcode.NEW_ARRAY) {
                    newArrayRefCount.merge(ref.instruction().id(), 1, Integer::sum);
                }
            }
        }
        int tmpCounter = 0;
        for (var e : newArrayRefCount.entrySet()) {
            if (e.getValue() > 1) {
                multiRefArrayVar.put(e.getKey(), "tmp" + tmpCounter++);
            }
        }
        currentMultiRefArrayVar = Collections.unmodifiableMap(multiRefArrayVar);

        // 仅将根指令生成为语句
        List<Statement> stmts = new ArrayList<>();
        // 为多次引用的 NEW_ARRAY 先发出临时变量声明
        for (var e : multiRefArrayVar.entrySet()) {
            for (IrInstruction insn : allInsns) {
                if (insn.id() == e.getKey()) {
                    stmts.add(new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                            insn.resultType(), e.getValue(),
                            (Expression) translateExpr(insn)));
                    break;
                }
            }
        }
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

            // 跳过 switch 表达式 follow 的 STORE/RETURN←PHI(值已由 case 体解析)
            if (isSkippedInstruction(insn.id())) {
                continue;
            }

            // 仅输出有副作用的指令作为语句
            if (StatementUtils.isStatementRoot(insn)) {
                Statement s = translateStmt(insn);

                if (s != null) {
                    // 后置自增折叠: println(v); v++; → println(v++),
                    // 或 v++; println(v) → println(v++).
                    // 字节码先 LOAD v 再 IINC,两者相邻且前一(或后一)
                    // 语句引用了被自增的变量时,自增应折叠为后置表达式,
                    // 否则输出的 println(v) 会读到自增后的值(语义偏差).
                    if (s instanceof ExpressionStatement es
                            && StatementUtils.isPostIncDec(es.expression())) {
                        UnExpr inc = (UnExpr) es.expression();
                        // 仅局部变量自增参与折叠;字段自增(this.counter++)
                        // 直接作为独立语句输出.
                        if (!(inc.operand() instanceof VarExpr target)) {
                            stmts.add(s);
                            continue;
                        }
                        // 向后折叠:自增语句紧随引用该变量的语句之后
                        if (!stmts.isEmpty()) {
                            Statement folded = StatementUtils.foldPostInc(stmts.get(stmts.size() - 1),
                                    target.name(), inc.operator());
                            if (folded != null) {
                                stmts.set(stmts.size() - 1, folded);
                                continue; // 跳过独立的自增语句
                            }
                        }
                        // 向前折叠:自增语句在引用该变量的语句之前
                        if (pendingPostInc == null) {
                            pendingPostInc = s;
                            continue;
                        }
                        stmts.add(pendingPostInc);
                        pendingPostInc = s;
                        continue;
                    }
                    if (pendingPostInc != null) {
                        UnExpr inc = (UnExpr) ((ExpressionStatement) pendingPostInc).expression();
                        if (!(inc.operand() instanceof VarExpr target)) {
                            stmts.add(pendingPostInc);
                            pendingPostInc = null;
                            stmts.add(s);
                            continue;
                        }
                        Statement folded = StatementUtils.foldPostInc(s, target.name(), inc.operator());
                        if (folded != null) {
                            stmts.add(folded);
                            pendingPostInc = null;
                            continue;
                        }
                        stmts.add(pendingPostInc);
                        pendingPostInc = null;
                    }
                    stmts.add(s);
                }
            } else if (!consumed.contains(insn.id()) && insn.resultValue() != null) {
                // 独立表达式(结果未被任何指令消费)——仍输出
                Expression e = translateExpr(insn);
                if (e != null && !StatementUtils.isIgnorableExpr(e)) {
                    stmts.add(new ExpressionStatement(e));
                }
            } else if (insn.opcode() == IrOpcode.NEW
                    && currentNewToInit.containsKey(insn.id())
                    && isBareConstruction(insn, allInsns, currentNewToInit.get(insn.id()))) {
                // 裸 new 表达式语句(构造器有副作用,结果被 pop):
                // new FileInputStream("x") —— 结果仅被合并的 <init> 消费,
                // 不能因"结果被消费"而丢弃整条语句.
                Expression e = translateExpr(insn);
                if (e != null && !StatementUtils.isIgnorableExpr(e)) {
                    stmts.add(new ExpressionStatement(e));
                }
            }
        }

        // 冲刷待折叠的后置自增(必须在空检查之前——
        // 仅有自增语句的组其 stmts 为空但 pendingPostInc 非空)
        if (pendingPostInc != null) {
            stmts.add(pendingPostInc);
            pendingPostInc = null;
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
                    retVal = AstCleanup.applyBooleanAnnotation(insn, retVal);
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
                // 合成异常占位符(slot < 0):handler 入口由 JVM 隐式压栈的
                // 异常对象. catch 子句的变量已由 catch 声明绑定,
                // 此 STORE 不产生语句.
                if (insn.operands().size() >= 2
                        && insn.operands().get(1) instanceof Variable sv && sv.slot() < 0) {
                    yield null;
                }
                // 对每个逻辑变量的首次存储产生 "Type name = value;".
                // 同时使用 slot+version:任意 slot 上的 version 1 总是首个局部变量定义
                //(version 0 = 参数).同时使用按作用域的追踪,
                // 使相同 slot 上不同分支体的临时变量各自获得独立的声明.
                Value target = insn.operands().getFirst();
                // 排除参数(已在方法签名中声明)与 this 接收者.
                // 注意不能用 slot() != 0 排除 this:静态方法里首个局部变量
                // 同样落在 slot 0,会被误跳过声明,导致 SourceCleanup 用
                // "int var = 0" 兜底(类型推断失效的根因).
                if (target instanceof Variable v && !v.isParameter()
                        && !"this".equals(v.name())) {
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
                        // 声明类型:JVM 将 boolean 存为 int 0/1,变量类型可能是 INT;
                        // 若初始化式为布尔表达式(如 boolean r = a && b 的短路合并),
                        // 声明须重标为 boolean,否则 "int r = a && b" 无法编译.
                        JavaType declType = v.genericType() != null
                                ? v.genericType() : v.type();
                        if (declType.kind() == TypeKind.INT && isBooleanExpression(rhs)) {
                            declType = JavaType.BOOLEAN;
                        }
                        // 局部变量上的 JSR-308 类型注解(0x40):渲染后按
                        // 类型路径分组附加到声明(如 "@A String x")
                        yield new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                                declType, declName, rhs, renderVarTypeAnnotations(v));
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
            params = StatementUtils.buildParamsFromDescriptor(implDescriptor);
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
                return LambdaExpr.methodRef(ExpressionTranslator.simplifyClassName(implOwner), "new", funcType);
            }
            // 方法引用:确定 owner 的表示方式
            String owner = ExpressionTranslator.simplifyClassName(implOwner);
            // 对于带捕获接收者的实例方法引用,使用变量名
            if (!operands.isEmpty() && operands.getFirst() instanceof Variable v
                    && v.name() != null && !"this".equals(v.name())) {
                owner = v.name();
            }
            return LambdaExpr.methodRef(owner, implName, funcType);
        }

        // 回退启发式:如果名称是 SAM 方法名(非 lambda$),且返回类型类似函数式接口,猜测方法引用
        if (NameUtils.isSamMethodName(mName) && NameUtils.isFunctionalInterfaceLike(funcType)) {
            String owner = NameUtils.functionalInterfaceShortName(funcType);
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
                    UnaryOperator incOp = StatementUtils.detectIncrement(bin);
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
                            String simple = lastSlash >= 0
                                    ? dc.substring(lastSlash + 1) : dc;
                            // 内部类/内部枚举(如 EnumSwitchCheck$Color 的静态字段 RED):
                            // 源码中用其简单名引用(Color.RED),不能保留 $ 二进名.
                            int lastDollar = simple.lastIndexOf('$');
                            obj = new VarExpr(lastDollar >= 0
                                    ? simple.substring(lastDollar + 1) : simple);
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
                // 操作数布局:静态字段(PUTSTATIC)仅 [value],实例字段(PUTFIELD)为 [obj, value].
                // 值始终是最后一个操作数;对象仅实例字段存在.按布局取,避免静态字段被
                // 误当成 obj,值被解析为 null 而回退 varUnresolved.
                Value val = !insn.operands().isEmpty() ? insn.operands().getLast() : null;
                Value obj = insn.operands().size() > 1 ? insn.operands().getFirst() : null;
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
                    UnaryOperator incOp = StatementUtils.detectIncrement(bin);
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
                    // 短路合并(boolean r = a && b)的 r 在字节码中存为 int 0/1,
                    // 但其定义 STORE←PHI 已被折叠为布尔表达式——同样按布尔处理.
                    boolean leftIsBool = StatementUtils.isBooleanValue(leftOp)
                            || isBooleanPhiReplacedVariable(leftOp);
                    boolean rightIsBool = StatementUtils.isBooleanValue(rightOp)
                            || isBooleanPhiReplacedVariable(rightOp);
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
                    UnaryOperator uop = StatementUtils.inferUnaryOp(insn.originalOpcode());
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
                yield new CastExpr(insn.resultType(), operand,
                        renderOffsetTypeAnnotations(
                                com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_CAST,
                                insn.sourceOffset()));
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
                    yield new NewExpr(insn.resultType(), List.of(), ctorArgs,
                            List.of(), List.of(), renderOffsetTypeAnnotations(
                            com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_NEW,
                            insn.sourceOffset()));
                }
                // NewExpr 构造函数为 (type, dimensions, constructorArgs)
                yield new NewExpr(insn.resultType(), List.of(), List.of(),
                        List.of(), List.of(), renderOffsetTypeAnnotations(
                        com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_NEW,
                        insn.sourceOffset()));
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
                yield new NewExpr(insn.resultType(), dims, List.of(),
                        List.of(), List.of(), renderOffsetTypeAnnotations(
                        com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_NEW,
                        arrayExprStartOffset(insn)));
            }

            // instanceof:nameHint 携带目标类内部名
            case INSTANCE_OF -> {
                Expression obj = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("obj");
                JavaType checkedType = insn.nameHint() != null
                        ? JavaType.classType(insn.nameHint())
                        : JavaType.classType("java/lang/Object");
                yield new InstanceOfExpr(obj, checkedType,
                        renderOffsetTypeAnnotations(
                                com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_INSTANCEOF,
                                insn.sourceOffset()));
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
                // 若数组操作数是多次引用的 NEW_ARRAY,
                // 使用临时变量而非内联 new 表达式(语义正确性).
                if (!insn.operands().isEmpty()
                        && insn.operands().get(0) instanceof InstructionRef ref
                        && currentMultiRefArrayVar.containsKey(ref.instruction().id())) {
                    arr = new VarExpr(currentMultiRefArrayVar.get(ref.instruction().id()));
                }
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
                    // x += c → 若 c == 1 则转为 x++,c == -1 转为 x--
                    if (rhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                            && lr.value() instanceof Integer i) {
                        if (i == 1) {
                            yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                    com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, var);
                        }
                        if (i == -1) {
                            yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                    com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, var);
                        }
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
                // 条件赋值折叠已把此 PHI 折叠为三元表达式(见 IfTranslator)——
                // 后续 STORE 翻译时直接返回折叠结果,避免丢 false 分支值.
                Expression replaced = phiReplacements.get(insn.id());
                if (replaced != null) {
                    yield replaced;
                }
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
                    // value 位置(无分支上下文)的三元重建:PHI 作为 CONDITION/BINARY
                    // 等指令的操作数被消费时,按 CFG 菱形还原为 CondExpr,避免取首
                    // 操作数静默丢 false 分支值.
                    if (currentBranchBlocks == null && currentIr != null) {
                        resolved = IfTranslator.resolvePhiAsTernary(this, insn, currentIr);
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
                        if (op instanceof com.bingbaihanji.bdec.ir.DynamicConstantValue dcv) {
                            resolved = ExpressionTranslator.dynamicConstToExpr(dcv);
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
    public Expression valueToExpr(Value v) {
        // 多次引用的 NEW_ARRAY 使用临时变量(而非内联 new)
        if (v instanceof InstructionRef ref
                && currentMultiRefArrayVar.containsKey(ref.instruction().id())) {
            return new VarExpr(currentMultiRefArrayVar.get(ref.instruction().id()));
        }
        return ExpressionTranslator.valueToExpr(v, currentVarStoreSource, this::translateExpr);
    }

    /** 变量 v 是否由布尔 phiReplacement 定义(短路合并 boolean r = a && b 的 r,
     *  字节码存为 int 0/1,但其定义 STORE←PHI 已被 IfTranslator 折叠为布尔表达式). */
    private boolean isBooleanPhiReplacedVariable(Value v) {
        if (!(v instanceof Variable var) || currentIr == null) {
            return false;
        }
        for (IrInstruction insn : currentIr.instructions()) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                    && insn.operands().get(0) instanceof Variable sv
                    && sv.slot() == var.slot() && sv.version() == var.version()
                    && insn.operands().get(1) instanceof InstructionRef ref
                    && ref.instruction().opcode() == IrOpcode.PHI
                    && phiReplacements.containsKey(ref.instruction().id())) {
                return true;
            }
        }
        return false;
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

    /** 按指定块上下文解析 follow 块中的 PHI 值.
     *
     *  <p>PHI 操作数按 {@code cfg.predecessorsOf(follow)} 的前驱顺序排列(IrBuilder
     *  的 {@code mergePredecessorStates} 依次取各前驱的栈值).因此直接按前驱
     *  顺序将分支块与 PHI 操作数对齐,而非仅靠 {@link InstructionRef#blockId()}
     *  匹配——参数等直接以 {@link Variable} 形式入栈的操作数不携带块 id,
     *  靠 blockId 匹配会漏掉它们(嵌套三元 {@code a ? (b ? c : d) : e} 的叶值
     *  即此情形,曾一律取首操作数 {@code c} 而丢 {@code d}/{@code e}).</p> */
    public Expression resolvePhiAt(BasicBlock follow, LinearIr ir) {
        if (follow == null) {
            return null;
        }
        for (IrInstruction fi : ir.instructionsOf(follow)) {
            if (fi.opcode() != IrOpcode.PHI) {
                continue;
            }
            if (currentBranchBlocks != null) {
                List<BasicBlock> preds = ir.controlFlowGraph().predecessorsOf(follow);
                for (int i = 0; i < preds.size() && i < fi.operands().size(); i++) {
                    if (currentBranchBlocks.contains(preds.get(i).id())) {
                        return valueToExpr(fi.operands().get(i));
                    }
                }
            }
            return valueToExpr(new InstructionRef(fi, fi.resultType()));
        }
        return null;
    }

    /** 注册 PHI 折叠结果:后续 STORE 翻译时,该 PHI 解析为给定三元表达式. */
    @Override
    public void registerPhiReplacement(int phiId, Expression expr) {
        if (phiReplacements.isEmpty()) {
            phiReplacements = new HashMap<>();
        }
        phiReplacements.put(phiId, expr);
    }

    /** 注册待跳过的指令 ID(switch 表达式 follow 的 STORE/RETURN←PHI). */
    @Override
    public void registerSkippedInstruction(int insnId) {
        if (currentSkipInsns.isEmpty()) {
            currentSkipInsns = new HashSet<>();
        }
        currentSkipInsns.add(insnId);
    }

    /** 该指令 ID 是否已被注册为跳过. */
    @Override
    public boolean isSkippedInstruction(int insnId) {
        return currentSkipInsns.contains(insnId);
    }

    /**
     * 判断 NEW 指令是否为"裸 new 语句":其结果仅被合并的 {@code <init>} 调用
     * 消费,随后被 pop(无任何 STORE/字段赋值/数组元素等真正消费方).
     *
     * <p>构造器本身有副作用({@code new FileInputStream("x")}),即便结果被丢弃
     * 也必须作为表达式语句输出,而不能因"结果被消费"而整体消失.</p>
     */
    private boolean isBareConstruction(IrInstruction newInsn, List<IrInstruction> allInsns,
                                       List<IrInstruction> mergedInits) {
        Set<Integer> initIds = new HashSet<>();
        for (IrInstruction init : mergedInits) {
            initIds.add(init.id());
        }
        for (IrInstruction insn : allInsns) {
            if (initIds.contains(insn.id())) {
                continue; // 合并的 <init> 不算真正消费方
            }
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref && ref.instruction().id() == newInsn.id()) {
                    return false; // 被非 <init> 的指令消费(如 STORE/字段赋值)
                }
            }
        }
        return true;
    }

    /** 将处理器指令(去除最后的 THROW)翻译为 Statement 体.
     *  直接翻译收集到的处理器指令,而不依赖 BlockGroup/块分组,
     *  后者可能遗漏分割的处理器片段. */
    @Override
    public Statement translateHandlerWithoutThrow(TryCatchInfo info, LinearIr ir,
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
            if (StatementUtils.isStatementRoot(insn)) {
                Statement s = translateStmt(insn);
                if (s != null) {
                    stmts.add(s);
                }
            } else if (!consumed.contains(insn.id()) && insn.resultValue() != null) {
                Expression e = translateExpr(insn);
                if (e != null && !StatementUtils.isIgnorableExpr(e)) {
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


    /** 将单个基本块组翻译为语句列表 */
    public List<Statement> translateBlockGroup(BlockGroup group, LinearIr ir) {
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

    @Override
    public List<TryCatchInfo> tryCatchAnnotations() {return currentTryCatchAnns;}

    @Override
    public void pushDeclaredScope() {
        declaredVarNameStack.push(new HashSet<>());
    }

    @Override
    public void popDeclaredScope() {
        declaredVarNameStack.pop();
    }

    @Override
    public java.util.Set<Integer> currentBranchBlocks() {return currentBranchBlocks;}

    @Override
    public void setCurrentBranchBlocks(java.util.Set<Integer> blockIds) {
        currentBranchBlocks = blockIds;
    }

    @Override
    public LoopInfo loopAnnotation(BasicBlock b) {return currentLoopAnns.get(b);}

    @Override
    public SwitchInfo switchAnnotation(BasicBlock b) {return currentSwitchAnns.get(b);}


    @Override
    public Statement pendingPostInc() {return pendingPostInc;}

    @Override
    public void setPendingPostInc(Statement s) {pendingPostInc = s;}

}
