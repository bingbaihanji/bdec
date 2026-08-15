package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
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
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * if 翻译器——从 {@link BlockReducer} 中按 Vineflower "每模式一处理器"
 * 风格提取的 if 专用翻译逻辑.
 *
 * <p>包含:if-header 检测({@link #detectIfHeader} 按 CFG 结构与后支配树
 * 计算合并点),分支体翻译({@link #translateBranchBody} 递归结构化嵌套
 * if/loop/switch),布尔 return 折叠与 short-branch-first 规范化
 * ({@link #translateIf}).依赖归约状态的能力(表达式翻译,作用域追踪,
 * PHI 分支上下文)通过 {@link ReducerOps} 回调 {@link BlockReducer},
 * 本类保持无状态.</p>
 */
public final class IfTranslator {

    private IfTranslator() {}

    /**
     * 翻译 if 结构(ifInfo → IfStatement),reduce() 主循环的 if 分支.
     *
     * <p>包含:条件提取回退链(组内 → IfInfo 头部 → 全局扫描),布尔 return
     * 折叠(空分支 + 尾部 PHI → {@code return cond}),short-branch-first
     * 规范化(大 then + 简单终止 else → {@code if (!cond) { 小 } 大块}).</p>
     */
    static Statement translateIf(ReducerOps ops, IfInfo ifInfo, BlockGroup group, LinearIr ir,
                                 List<BlockGroup> groups, Set<BlockGroup> consumed,
                                 ControlFlowGraph graph, PostDominatorTree postDom) {
        // 优先从组内提取条件;回退方案依次尝试 IfInfo 头部块及全部组
        Expression rawCond = ops.extractCondition(group, ir);
        if (rawCond == null) {
            rawCond = ops.extractConditionFromHeader(ifInfo.header(), ir);
        }
        if (rawCond == null) {
            rawCond = ops.extractConditionFromAllGroups(groups, ir);
        }
        Expression cond = AstCleanup.simplifyCondition(rawCond);

        // 翻译头部组中的非条件语句.
        // 当包含 if-header 的组中也包含在条件之前执行的代码时
        //(例如在最终三元条件之前的位操作),这些代码必须出现在 IfStatement 之前.
        List<Statement> preIfStmts = ops.translateHeaderNonCondition(group, ir);

        // 布尔短路折叠:return a && b / return a || b(javac 编译为共享假/真目标的
        // 嵌套条件,直接翻译成嵌套 if 会丢失假路径的 return,布尔方法缺 return
        // 无法编译).识别后折叠为 return a OP b.
        Statement shortCircuit = foldBooleanShortCircuit(ops, ifInfo, ir, graph,
                groups, consumed, cond, preIfStmts);
        if (shortCircuit != null) {
            return shortCircuit;
        }

        // 翻译 then 体:找出包含 then 块的组
        Statement thenBody = translateBranchBody(ops, ifInfo.thenBlocks(), groups, ir, consumed, graph, postDom);

        // 翻译 else 体:找出包含 else 块的组
        Statement elseBody = null;
        if (!ifInfo.elseBlocks().isEmpty()) {
            elseBody = translateBranchBody(ops, ifInfo.elseBlocks(), groups, ir, consumed, graph, postDom);
        }

        // 消除空 else 块——不输出 "else { }"
        if (StatementUtils.isEmptyBlock(elseBody)) {
            elseBody = null;
        }

        // 对 if-else 模式的分支体进行后处理:两个分支都在共同的
        // RETURN 块处合并计算值.
        boolean thenHasReturn = ReturnNormalizer.hasReturnStmt(thenBody);
        boolean elseHasReturn = ReturnNormalizer.hasReturnStmt(elseBody);
        boolean isBoolRet = ir.method().returnType() != null
                && ir.method().returnType().kind() == TypeKind.BOOLEAN;
        boolean isVoidRet = ir.method().returnType() != null
                && ir.method().returnType().kind() == TypeKind.VOID;

        if (thenHasReturn != elseHasReturn) {
            if (thenHasReturn) {
                thenBody = ReturnNormalizer.stripOrphanExprs(thenBody);
                if (elseBody != null) {
                    elseBody = ReturnNormalizer.wrapAsReturn(elseBody, isBoolRet, isVoidRet);
                }
            } else {
                if (thenBody != null) {
                    thenBody = ReturnNormalizer.wrapAsReturn(thenBody, isBoolRet, isVoidRet);
                }
                elseBody = ReturnNormalizer.stripOrphanExprs(elseBody);
            }
        }

        // 布尔 return 折叠:两个分支体均为空(分支的 CONST
        // 汇入尾部 return 的 PHI),如 lambda 谓词
        // "if (len<=3) { } else { } return phi" 实为
        // return !(len<=3).按 TRUE 分支的 PHI 值决定取反.
        if (StatementUtils.isEmptyBlock(thenBody) && (elseBody == null || StatementUtils.isEmptyBlock(elseBody))
                && ir.method().returnType() != null
                && ir.method().returnType().kind() == TypeKind.BOOLEAN
                && cond != null) {
            BasicBlock ifFollow = ifInfo.follow();
            Expression trueVal = null;
            Set<Integer> prevCtx = ops.currentBranchBlocks();
            Set<Integer> branchCtx = new HashSet<>();
            for (BasicBlock tb : ifInfo.thenBlocks()) {
                branchCtx.add(tb.id());
            }
            try {
                ops.setCurrentBranchBlocks(branchCtx);
                trueVal = ops.resolvePhiAt(ifFollow, ir);
            } finally {
                ops.setCurrentBranchBlocks(prevCtx);
            }
            if (trueVal instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                    && lit.value() instanceof Number n) {
                Expression retCond = n.intValue() == 0
                        ? AstCleanup.negateCond(cond) : cond;
                // 消费 if 的 follow 组(尾部 return 由本折叠替代)
                for (BlockGroup fg : groups) {
                    if (fg.blocks().contains(ifFollow)) {
                        consumed.add(fg);
                    }
                }
                return new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(retCond);
            }
        }

        // 合并 return 三元折叠:两个分支体的表达式汇入尾部 return 的 PHI,
        // 实为 "return cond ? trueVal : falseVal".例:"return b ? 1 : 2" 被
        // javac 编译为单一 ireturn(goto 汇合),PHI 在 return 块合并两分支值.
        // 布尔 return 已由上方布尔折叠处理,此处覆盖非布尔及复杂表达式情形.
        if (!ifInfo.negateCondition()
                && isTernaryShaped(thenBody)
                && isTernaryShaped(elseBody)
                && cond != null
                && !ifInfo.elseBlocks().isEmpty()
                && isTailReturnPhi(ifInfo.follow(), ir)) {
            BasicBlock ifFollow = ifInfo.follow();
            Expression trueVal = resolveBranchValue(ops, ifFollow, ir, ifInfo.thenBlocks(), graph);
            Expression falseVal = resolveBranchValue(ops, ifFollow, ir, ifInfo.elseBlocks(), graph);
            if (trueVal != null && falseVal != null) {
                // 消费 if 的 follow 组(尾部 return 由本折叠替代)
                for (BlockGroup fg : groups) {
                    if (fg.blocks().contains(ifFollow)) {
                        consumed.add(fg);
                    }
                }
                // 规范化:!x ? a : b → x ? b : a(与 TernaryRewriter 一致)
                Statement ret;
                if (cond instanceof UnExpr ue && ue.operator() == UnaryOperator.NOT) {
                    ret = new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(
                            new CondExpr(ue.operand(), falseVal, trueVal));
                } else {
                    ret = new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(
                            new CondExpr(cond, trueVal, falseVal));
                }
                // 前置头组的非条件语句(如 Integer a = 1000; b = 1000; 位于
                // 条件之前)——此前直接返回会丢失这些声明(引用未声明变量).
                if (!preIfStmts.isEmpty()) {
                    List<Statement> combined = new ArrayList<>(preIfStmts);
                    combined.add(ret);
                    return new BlockStatement(combined);
                }
                return ret;
            }
        }

        // 合并条件赋值折叠:两个分支体的表达式汇入尾部 STORE 的 PHI,
        // 实为 "Type y = cond ? trueVal : falseVal".例:"int y = b ? 1 : 2"
        // 被 javac 编译为单一 istore(goto 汇合),PHI 在 store 块合并两分支值.
        // 此处仅注册 PHI 折叠结果并抑制空 if,声明语句由 follow 组后续翻译生成.
        if (!ifInfo.negateCondition()
                && isTernaryShaped(thenBody)
                && isTernaryShaped(elseBody)
                && cond != null
                && !ifInfo.elseBlocks().isEmpty()) {
            IrInstruction store = findStoreOfPhi(ifInfo.follow(), ir);
            if (store != null && store.operands().size() >= 2
                    && store.operands().get(1) instanceof InstructionRef ref) {
                BasicBlock ifFollow = ifInfo.follow();
                Expression trueVal = resolveBranchValue(ops, ifFollow, ir, ifInfo.thenBlocks(), graph);
                Expression falseVal = resolveBranchValue(ops, ifFollow, ir, ifInfo.elseBlocks(), graph);
                if (trueVal != null && falseVal != null) {
                    IrInstruction phi = ref.instruction();
                    // 规范化:!x ? a : b → x ? b : a(与 TernaryRewriter 一致)
                    Expression ternary = (cond instanceof UnExpr ue
                            && ue.operator() == UnaryOperator.NOT)
                            ? new CondExpr(ue.operand(), falseVal, trueVal)
                            : new CondExpr(cond, trueVal, falseVal);
                    ops.registerPhiReplacement(phi.id(), ternary);
                    // 抑制空 if,声明由 follow 组生成;但头组的非条件语句
                    //(条件之前的局部声明)必须保留,否则引用未声明变量.
                    return preIfStmts.isEmpty() ? null : new BlockStatement(preIfStmts);
                }
            }
        }

        // 当 then 体来自 false 分支(trueTarget==follow)时,
        // CONDITION 需要取反以产生正确的 Java 语义.
        // 例如:ifeq→CONDITION 翻译为 !(值),但 then 体是 false 分支
        // (值!=0 时的代码),因此需要再次取反以还原为原始 boolean 值.
        if (ifInfo.negateCondition() && cond != null) {
            cond = new UnExpr(UnaryOperator.NOT, cond);
            cond = AstCleanup.simplifyCondition(cond);
        }

        // if 结构规范化:then 体显著大于 else 且 else 是简单终止语句
        //(throw/return)时,倒转为 if (!cond) { 小 } 后接大块——
        // 源码通常把异常/提前返回的短分支写在前面
        //(CFR/Vineflower 的 short-branch-first 启发式).
        // 例:if (x >= 0) { ...大块... } else { throw } → if (x < 0) { throw } ...大块...
        if (elseBody != null && AstCleanup.isSimpleTerminal(elseBody)
                && !AstCleanup.isSimpleTerminal(thenBody)
                && AstCleanup.countStatements(thenBody) > AstCleanup.countStatements(elseBody) * 2
                && cond != null) {
            Statement smallIf = new IfStatement(AstCleanup.negateCond(cond), elseBody, null);
            List<Statement> combined = new ArrayList<>(preIfStmts);
            combined.add(smallIf);
            combined.add(thenBody);
            return new BlockStatement(combined);
        }

        Statement s = new IfStatement(cond != null ? cond : new com.bingbaihanji.bdec.ast.expr.LitExpr(true, JavaType.BOOLEAN),
                thenBody != null ? thenBody : new BlockStatement(List.of()),
                elseBody);

        // 如有前导语句,将其前置到 IfStatement 之前
        if (!preIfStmts.isEmpty()) {
            List<Statement> combined = new ArrayList<>(preIfStmts);
            combined.add(s);
            s = new BlockStatement(combined);
        }
        return s;
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
    static IfInfo detectIfHeader(BlockGroup group, ControlFlowGraph graph, LinearIr ir,
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

            // 终止分支检测:一个分支的所有路径直达 exit 且目标块无外部前驱
            //(即该分支是 throw/return 的终止分支),此时 follow 应为
            // 另一分支的目标(延续点).
            // 例:assert 模式 if (x > 0) {} else throw——FALSE 分支直接
            // athrow 到 exit,TRUE 目标是方法的后续代码(有外部前驱).
            if (falseTarget != null
                    && isTerminalBranch(falseTarget, trueTarget, b, graph)
                    && hasExternalPred(trueTarget, b, graph)) {
                follow = trueTarget;
            } else if (trueTarget != null
                    && isTerminalBranch(trueTarget, falseTarget, b, graph)
                    && hasExternalPred(falseTarget, b, graph)) {
                follow = falseTarget;
            }

            // 若某一后继即为 follow,则是 if-then(无 else)
            // 若两个后继均不是 follow,则两个分支最终都到达 follow → if-else
            Set<BasicBlock> thenBlocks, elseBlocks;
            boolean negateCondition = false;
            // 特判:一个分支在另一个分支的目标处汇入(一个分支是"跳过"分支).
            // 例:assert 的 $assertionsDisabled 检查——TRUE 直接落到后续代码,
            // FALSE 是 assert 体,最终也汇入后续代码.
            // 此时被汇入分支是空的"跳过",if 应取反后只包含另一分支,
            // 后续代码不属于 if.
            boolean falseJoinsTrue = branchJoins(falseTarget, trueTarget, follow, graph);
            boolean trueJoinsFalse = branchJoins(trueTarget, falseTarget, follow, graph);
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
            } else if (falseJoinsTrue) {
                // false 分支汇入 true 目标:then = false 分支(条件取反),
                // 收敛点 = true 目标,后续代码不属于 if.
                thenBlocks = collectReachableBlocks(falseTarget, trueTarget, graph);
                elseBlocks = Set.of();
                negateCondition = true;
                follow = trueTarget;
            } else if (trueJoinsFalse) {
                // true 分支汇入 false 目标:then = true 分支(条件不变),
                // 收敛点 = false 目标.
                thenBlocks = collectReachableBlocks(trueTarget, falseTarget, graph);
                elseBlocks = Set.of();
                follow = falseTarget;
            } else {
                // 两个分支都到达 follow → if-else
                thenBlocks = collectReachableBlocks(trueTarget, follow, graph);
                elseBlocks = collectReachableBlocks(falseTarget, follow, graph);
            }
            return new IfInfo(b, follow, thenBlocks, elseBlocks, negateCondition);
        }
        return null;
    }

    /** 检查目标块是否有 header 之外的前驱(即它是共享延续点) */
    static boolean hasExternalPred(BasicBlock target, BasicBlock header,
                                   ControlFlowGraph graph) {
        if (target == null) {
            return false;
        }
        for (var in : graph.incomingOf(target)) {
            if (in.source() != header) {
                return true;
            }
        }
        return false;
    }

    /** 检查分支是否为终止分支:目标块无外部前驱(仅从 header 进入),
     *  且所有路径都直达 exit,不汇入另一个分支的目标. */
    static boolean isTerminalBranch(BasicBlock start, BasicBlock otherTarget,
                                    BasicBlock header, ControlFlowGraph graph) {
        if (start == otherTarget || start == graph.exitBlock()) {
            return false;
        }
        // 目标块有 header 之外的前驱 → 它是共享延续点,不是终止分支
        for (var in : graph.incomingOf(start)) {
            if (in.source() != header) {
                return false;
            }
        }
        // 所有非异常路径都必须直达 exit
        Set<BasicBlock> visited = new HashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock curr = queue.poll();
            if (!visited.add(curr)) {
                continue;
            }
            if (curr == otherTarget) {
                return false; // 汇入了另一分支
            }
            for (var e : graph.outgoingOf(curr)) {
                if (e.kind() == EdgeKind.EXCEPTION) {
                    continue;
                }
                BasicBlock t = e.target();
                if (t == graph.exitBlock()) {
                    continue; // 正常终止
                }
                if (t == otherTarget) {
                    return false;
                }
                queue.add(t);
            }
        }
        return true;
    }

    /** 检查分支区域(从 start 到 stop 的可达块)中是否存在指向 joinTarget 的边,
     *  即该分支最终汇入另一个分支的目标块. */
    static boolean branchJoins(BasicBlock start, BasicBlock joinTarget,
                               BasicBlock stop, ControlFlowGraph graph) {
        if (start == joinTarget || joinTarget == null || start == stop) {
            return false;
        }
        Set<BasicBlock> region = collectReachableBlocks(start, stop, graph);
        for (BasicBlock rb : region) {
            for (var e : graph.outgoingOf(rb)) {
                if (e.kind() != EdgeKind.EXCEPTION && e.target() == joinTarget) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 收集从 start 出发可达,但不包含 stop 的所有块.
     *  仅沿非异常边(FALL_THROUGH,TRUE_BRANCH,FALSE_BRANCH,BACK_EDGE)遍历.
     *  绝不能沿异常边遍历,否则处理器块会被错误地包含在 if/else 分支体中. */
    static Set<BasicBlock> collectReachableBlocks(BasicBlock start, BasicBlock stop,
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
    static Statement translateBranchGroup(ReducerOps ops, BlockGroup group, LinearIr ir,
                                          List<BlockGroup> allGroups,
                                          Set<BlockGroup> consumed,
                                          ControlFlowGraph graph,
                                          PostDominatorTree postDom) {
        // 检查此组是否为 switch 头(switch 在分支体内也必须结构化)
        for (BasicBlock gb : group.blocks()) {
            SwitchInfo swInfo = ops.switchAnnotation(gb);
            if (swInfo != null) {
                return SwitchTranslator.buildSwitch(ops, swInfo, group, ir, allGroups, consumed, graph);
            }
        }
        // 检查此组是否为循环头
        for (BasicBlock gb : group.blocks()) {
            LoopInfo lpInfo = ops.loopAnnotation(gb);
            if (lpInfo != null) {
                // 未折叠的循环(体内含 continue/break 内部分支):
                // 递归结构化循环体(与 reduce() 主循环一致)
                if (group.blocks().size() == 1 && group.first() == lpInfo.header()) {
                    Statement body = LoopTranslator.translateLoopBodyStructured(ops, lpInfo, allGroups, ir,
                            consumed, graph, postDom);
                    if (body != null && !StatementUtils.isEmptyBlock(body)) {
                        return LoopTranslator.wrapLoopStatement(ops, lpInfo, body, ops.extractCondition(group, ir));
                    }
                }
                Statement body = ops.translateGroup(group, ir);
                return LoopTranslator.wrapLoopStatement(ops, lpInfo, body, ops.extractCondition(group, ir));
            }
        }
        // synchronized 块:MONITOR_ENTER 带 SYNCHRONIZED_BLOCK 标记的组
        // 直接包装为 SynchronizedStatement(分支体内的 sync 同样处理).
        // sync 体通常位于后继组中(monitorenter 与 monitorexit 之间).
        if (SynchronizedTranslator.groupHasSynchronizedAnnotation(group, ir)) {
            Statement st = ops.translateGroup(group, ir);
            Statement syncBody = SynchronizedTranslator.collectSyncBody(ops, group, allGroups, consumed, ir, graph);
            List<Statement> full = new ArrayList<>();
            if (st != null) {
                full.add(st);
            }
            if (syncBody != null) {
                full.add(syncBody);
            }
            return SynchronizedTranslator.wrapSynchronized(StatementUtils.blockOf(full), group, ir);
        }
        // 尝试检测此组是否为嵌套 if-header
        IfInfo nestedIf = detectIfHeader(group, graph, ir, postDom);
        if (nestedIf != null) {
            Expression cond = AstCleanup.simplifyCondition(ops.extractCondition(group, ir));

            // then 体来自 false 分支时条件需取反(与 reduce() 主循环一致)
            if (nestedIf.negateCondition() && cond != null) {
                cond = new UnExpr(UnaryOperator.NOT, cond);
                cond = AstCleanup.simplifyCondition(cond);
            }

            // 翻译头部中的前导条件语句
            List<Statement> preIfStmts = ops.translateHeaderNonCondition(group, ir);

            Statement thenBody = translateBranchBody(ops, nestedIf.thenBlocks(), allGroups,
                    ir, consumed, graph, postDom);
            Statement elseBody = null;
            if (!nestedIf.elseBlocks().isEmpty()) {
                elseBody = translateBranchBody(ops, nestedIf.elseBlocks(), allGroups,
                        ir, consumed, graph, postDom);
            }
            if (StatementUtils.isEmptyBlock(elseBody)) {
                elseBody = null;
            }

            // 后处理分支体
            boolean thenHasReturn = ReturnNormalizer.hasReturnStmt(thenBody);
            boolean elseHasReturn = ReturnNormalizer.hasReturnStmt(elseBody);
            boolean isBoolRet = ir.method().returnType() != null
                    && ir.method().returnType().kind() == TypeKind.BOOLEAN;
            boolean isVoidRet = ir.method().returnType() != null
                    && ir.method().returnType().kind() == TypeKind.VOID;
            if (thenHasReturn != elseHasReturn) {
                if (thenHasReturn) {
                    thenBody = ReturnNormalizer.stripOrphanExprs(thenBody);
                    if (elseBody != null) {
                        elseBody = ReturnNormalizer.wrapAsReturn(elseBody, isBoolRet, isVoidRet);
                    }
                } else {
                    if (thenBody != null) {
                        thenBody = ReturnNormalizer.wrapAsReturn(thenBody, isBoolRet, isVoidRet);
                    }
                    elseBody = ReturnNormalizer.stripOrphanExprs(elseBody);
                }
            }

            // if 结构规范化(与 reduce() 主循环一致):
            // then 大块 + else 简单终止 → if (!cond) { 小 } 后接大块
            if (elseBody != null && AstCleanup.isSimpleTerminal(elseBody)
                    && !AstCleanup.isSimpleTerminal(thenBody)
                    && AstCleanup.countStatements(thenBody) > AstCleanup.countStatements(elseBody) * 2
                    && cond != null) {
                Statement smallIf = new IfStatement(AstCleanup.negateCond(cond), elseBody, null);
                List<Statement> combined = new ArrayList<>(preIfStmts);
                combined.add(smallIf);
                combined.add(thenBody);
                return new BlockStatement(combined);
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
        return ops.translateGroup(group, ir);
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
    static Statement translateBranchBody(ReducerOps ops, Set<BasicBlock> branchBlocks,
                                         List<BlockGroup> allGroups,
                                         LinearIr ir,
                                         Set<BlockGroup> consumed,
                                         ControlFlowGraph graph,
                                         PostDominatorTree postDom) {
        // 设置分支上下文用于 PHI 解析
        Set<Integer> prevBranchBlocks = ops.currentBranchBlocks();
        Set<Integer> branchBlockIds = new HashSet<>();
        for (BasicBlock b : branchBlocks) {
            branchBlockIds.add(b.id());
        }
        ops.setCurrentBranchBlocks(branchBlockIds);
        // 为此分支体压入新的变量声明作用域
        ops.pushDeclaredScope();
        try {
            List<Statement> bodyStmts = new ArrayList<>();
            // 每条语句对应的组索引,供分支体内的 try 区域包装使用
            List<Integer> bodyGroupIdx = new ArrayList<>();
            for (int gi = 0; gi < allGroups.size(); gi++) {
                BlockGroup g = allGroups.get(gi);
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
                    Statement stmt = translateBranchGroup(ops, g, ir, allGroups, consumed, graph, postDom);
                    if (stmt != null) {
                        bodyStmts.add(stmt);
                        bodyGroupIdx.add(gi);
                    }
                }
            }
            if (bodyStmts.isEmpty()) {
                return new BlockStatement(List.of());
            }
            // 将分支体内的 try 区域包装为 TryStatement(区域收集逻辑见
            // TryTranslator.collectBranchTryAnns).
            List<TryCatchInfo> branchTcis = TryTranslator.collectBranchTryAnns(
                    branchBlocks, ops.tryCatchAnnotations(), ir);
            if (!branchTcis.isEmpty()) {
                bodyStmts = TryTranslator.wrapTryStatements(ops, bodyStmts, bodyGroupIdx, allGroups,
                        branchTcis, ir);
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
            ops.setCurrentBranchBlocks(prevBranchBlocks);
            ops.popDeclaredScope(); // 弹出分支作用域
        }
    }

    /** 判断 follow 块是否为"尾部 return 的 PHI 汇合":块内 RETURN 的操作数是 PHI.
     *  仅当 follow 确实是值返回块时才折叠,避免把 {@code int y = b ? 1 : 2;}
     *  这类"PHI 汇入 STORE"误折叠为 return(丢失后续对 y 的使用). */
    private static boolean isTailReturnPhi(BasicBlock follow, LinearIr ir) {
        if (follow == null) {
            return false;
        }
        IrInstruction phi = null;
        IrInstruction ret = null;
        for (IrInstruction i : ir.instructionsOf(follow)) {
            if (i.opcode() == IrOpcode.PHI) {
                phi = i;
            } else if (i.opcode() == IrOpcode.RETURN) {
                ret = i;
            }
        }
        if (phi == null || ret == null || ret.operands().isEmpty()) {
            return false;
        }
        Value op = ret.operands().getFirst();
        return op instanceof InstructionRef ref && ref.instruction() == phi;
    }

    /** 查找 follow 块中"PHI 汇入 STORE"的 STORE 指令(即 PHI 是 STORE 的源值).
     *  条件赋值 {@code int y = b ? 1 : 2} 编译为单一 istore,PHI 在 store 块
     *  合并两分支值;返回 null 表示 follow 不是条件赋值汇合点. */
    private static IrInstruction findStoreOfPhi(BasicBlock follow, LinearIr ir) {
        if (follow == null) {
            return null;
        }
        IrInstruction phi = null;
        for (IrInstruction i : ir.instructionsOf(follow)) {
            if (i.opcode() == IrOpcode.PHI) {
                phi = i;
                break;
            }
        }
        if (phi == null) {
            return null;
        }
        for (IrInstruction i : ir.instructionsOf(follow)) {
            if (i.opcode() == IrOpcode.STORE && i.operands().size() >= 2
                    && i.operands().get(1) instanceof InstructionRef ref
                    && ref.instruction() == phi) {
                return i;
            }
        }
        return null;
    }

    /** 递归解析分支区域的值表达式(支持嵌套三元).
     *
     * <p>三元表达式链 {@code a ? (b ? c : d) : e} 编译后,所有分支值在同一个
     * follow 块的 stack-PHI 中汇合,内层三元作为外层三元的某个操作数出现.
     * 单纯按"当前分支上下文选单个 PHI 操作数"会丢失内层结构(取第一个操作数
     * {@code c},恒返回 {@code c} 而丢 {@code d},{@code e}).本方法在分支区域内
     * 递归查找嵌套的 CONDITION 头:若存在,则把该条件连同其 then/else 子区域
     * 递归解析为 CondExpr;否则回退到叶子解析({@link #resolveBranchPhi})取单个
     * PHI 操作数.</p>
     */
    private static Expression resolveBranchValue(ReducerOps ops, BasicBlock follow, LinearIr ir,
                                                 Set<BasicBlock> branchBlocks,
                                                 ControlFlowGraph graph) {
        // 在分支区域内查找顶层嵌套三元条件头(区域 BFS 序中首个 CONDITION 块).
        for (BasicBlock b : branchBlocks) {
            boolean hasCond = false;
            for (IrInstruction i : ir.instructionsOf(b)) {
                if (i.opcode() == IrOpcode.CONDITION) {
                    hasCond = true;
                    break;
                }
            }
            if (!hasCond) {
                continue;
            }
            BasicBlock trueTarget = null, falseTarget = null;
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() == EdgeKind.TRUE_BRANCH) {
                    trueTarget = e.target();
                } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                    falseTarget = e.target();
                }
            }
            // 条件头必须是真正的 if-else 三元(两个分支都不直达 follow),
            // 否则不是纯三元,回退到叶子解析.
            if (trueTarget == null || falseTarget == null
                    || trueTarget == follow || falseTarget == follow) {
                continue;
            }
            Set<BasicBlock> thenRegion = collectReachableBlocks(trueTarget, follow, graph);
            Set<BasicBlock> elseRegion = collectReachableBlocks(falseTarget, follow, graph);
            Expression cond = AstCleanup.simplifyCondition(ops.extractConditionFromHeader(b, ir));
            Expression trueVal = resolveBranchValue(ops, follow, ir, thenRegion, graph);
            Expression falseVal = resolveBranchValue(ops, follow, ir, elseRegion, graph);
            if (cond == null || trueVal == null || falseVal == null) {
                continue;
            }
            return new CondExpr(cond, trueVal, falseVal);
        }
        // 叶子:区域内无嵌套条件,直接按分支上下文解析单个 PHI 操作数.
        return resolveBranchPhi(ops, follow, ir, branchBlocks);
    }

    /** 按分支块上下文解析 follow 块中的 PHI 值(临时切换 branch 上下文). */
    private static Expression resolveBranchPhi(ReducerOps ops, BasicBlock follow, LinearIr ir,
                                               Set<BasicBlock> branchBlocks) {
        Set<Integer> prevCtx = ops.currentBranchBlocks();
        Set<Integer> branchCtx = new HashSet<>();
        for (BasicBlock b : branchBlocks) {
            branchCtx.add(b.id());
        }
        try {
            ops.setCurrentBranchBlocks(branchCtx);
            return ops.resolvePhiAt(follow, ir);
        } finally {
            ops.setCurrentBranchBlocks(prevCtx);
        }
    }

    /** 从 CFG 菱形结构把 value 位置的 PHI 还原为三元表达式.
     *
     * <p>三元作为某个表达式的操作数时(如外层三元的条件 {@code (a>0?b:c)>0},
     * 或复合赋值右操作数 {@code s += a[i]>0 ? 1 : 0}),其值在汇合块以 stack-PHI
     * 出现,但该 PHI 被无条件消费(作为 CONDITION/BINARY 操作数),没有分支上下文
     * 可选取单个操作数.此前的回退取首操作数会静默丢失 false 分支值(如输出
     * {@code s++} 恒加一).本方法依据 PHI 所在汇合块 M 及其直接支配者(菱形顶点
     * 条件块 C)重建 {@code cond ? trueVal : falseVal};无法识别为简单二元菱形时
     * 返回 null,交由调用方回退到单操作数解析.</p>
     */
    static Expression resolvePhiAsTernary(ReducerOps ops, IrInstruction phi, LinearIr ir) {
        if (phi == null || phi.operands().size() != 2) {
            return null;
        }
        // 拒绝循环携带 PHI:两个操作数为同一局部变量(槽位相同),是循环头
        // 汇合(init/back-edge),而非条件三元.这类 PHI 应解析为变量本身.
        if (phi.operands().get(0) instanceof Variable v0
                && phi.operands().get(1) instanceof Variable v1
                && v0.slot() == v1.slot()) {
            return null;
        }
        ControlFlowGraph graph = ir.controlFlowGraph();
        BasicBlock merge = null;
        for (BasicBlock b : graph.blocks()) {
            if (b.id() == phi.blockId()) {
                merge = b;
                break;
            }
        }
        if (merge == null) {
            return null;
        }
        // 汇合块须恰有两个非异常前驱(二元菱形)
        if (graph.predecessorsOf(merge).size() != 2) {
            return null;
        }
        // 菱形顶点 = 汇合块的直接支配者
        BasicBlock header = graph.dominatorTree().idom(merge);
        if (header == null || header == graph.entryBlock() || header == graph.exitBlock()) {
            return null;
        }
        boolean hasCond = false;
        for (IrInstruction i : ir.instructionsOf(header)) {
            if (i.opcode() == IrOpcode.CONDITION) {
                hasCond = true;
                break;
            }
        }
        if (!hasCond) {
            return null;
        }
        BasicBlock trueTarget = null, falseTarget = null;
        for (var e : graph.outgoingOf(header)) {
            if (e.kind() == EdgeKind.TRUE_BRANCH) {
                trueTarget = e.target();
            } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                falseTarget = e.target();
            }
        }
        if (trueTarget == null || falseTarget == null) {
            return null;
        }
        Set<BasicBlock> thenRegion = collectReachableBlocks(trueTarget, merge, graph);
        Set<BasicBlock> elseRegion = collectReachableBlocks(falseTarget, merge, graph);
        if (thenRegion.isEmpty() || elseRegion.isEmpty()) {
            return null;
        }
        Expression cond = AstCleanup.simplifyCondition(ops.extractConditionFromHeader(header, ir));
        Expression trueVal = resolveBranchValue(ops, merge, ir, thenRegion, graph);
        Expression falseVal = resolveBranchValue(ops, merge, ir, elseRegion, graph);
        if (cond == null || trueVal == null || falseVal == null) {
            return null;
        }
        return new CondExpr(cond, trueVal, falseVal);
    }

    /**
     * 布尔短路折叠:识别 {@code return a && b} / {@code return a || b}.
     *
     * <p>javac 将 {@code return a && b} 编译为共享假目标的嵌套条件
     * ({@code a ? (b ? return true : return false) : return false},&& 的 C2 假目标
     * 与 C1 假目标共享),或将 {@code return a || b} 编译为共享真目标
     * ({@code a ? return true : (b ? return true : return false)},|| 的 C2 真目标
     * 与 C1 真目标共享).直接翻译成嵌套 if 会丢失假路径的 {@code return false},
     * 导致布尔方法缺 return 无法编译.本方法识别共享目标菱形并折叠为
     * {@code return a OP b}.</p>
     */
    private static Statement foldBooleanShortCircuit(ReducerOps ops, IfInfo ifInfo, LinearIr ir,
                                                     ControlFlowGraph graph, List<BlockGroup> groups,
                                                     Set<BlockGroup> consumed, Expression cond,
                                                     List<Statement> preIfStmts) {
        BasicBlock header = ifInfo.header();
        BasicBlock c1True = null, c1False = null;
        for (var e : graph.outgoingOf(header)) {
            if (e.kind() == EdgeKind.TRUE_BRANCH) {
                c1True = e.target();
            } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                c1False = e.target();
            }
        }
        if (c1True == null || c1False == null) {
            return null;
        }

        // 识别共享目标菱形.从 CFG 实测:javac 将 && 与 || 都编译为
        // "嵌套条件在 C1 的假路径上":
        //   && (return a && b):C1.true → F, C1.false → C2;C2.true → F(共享), C2.false → T2
        //   || (return a || b):C1.true → T, C1.false → C2;C2.true → F2, C2.false → T(共享)
        // 共享目标值即短路值(&& 共享假值,|| 共享真值).
        BasicBlock c2 = null;
        if (isConditionBlock(c1False, ir)) {
            c2 = c1False;
        } else if (isConditionBlock(c1True, ir)) {
            c2 = c1True;
        }
        if (c2 == null) {
            return null;
        }
        BasicBlock c2True = null, c2False = null;
        for (var e : graph.outgoingOf(c2)) {
            if (e.kind() == EdgeKind.TRUE_BRANCH) {
                c2True = e.target();
            } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                c2False = e.target();
            }
        }
        if (c2True == null || c2False == null) {
            return null;
        }

        Expression nestedCond = AstCleanup.simplifyCondition(
                ops.extractConditionFromHeader(c2, ir));
        if (nestedCond == null) {
            return null;
        }

        Set<BasicBlock> consume = new HashSet<>();
        consume.add(c2);
        consume.add(c1True);
        consume.add(c1False);
        Set<BasicBlock> valueBlocks = new HashSet<>();
        Expression combined;
        if (c2 == c1False) {
            // 嵌套条件在 C1 假路径:共享目标 = c1True
            if (c1True == c2True) {
                // && :共享 = C2 真目标(假值),真值在 C2 假路径(C2.false).
                if (!blockEndsWithBooleanValue(c2False, true, ir)
                        || !blockEndsWithBooleanValue(c1True, false, ir)) {
                    return null;
                }
                consume.add(c2False);
                valueBlocks.add(c2False);
                valueBlocks.add(c1True);
                // 真值路径 = C1 假(!c1)且 C2 假(!c2)→ return !c1 && !c2
                combined = new BinExpr(BinaryOperator.AND,
                        AstCleanup.negateCond(cond), AstCleanup.negateCond(nestedCond));
            } else if (c1True == c2False) {
                // || :共享 = C2 假目标(真值),假值在 C2 真路径(C2.true).
                if (!blockEndsWithBooleanValue(c2True, false, ir)
                        || !blockEndsWithBooleanValue(c1True, true, ir)) {
                    return null;
                }
                consume.add(c2True);
                valueBlocks.add(c2True);
                valueBlocks.add(c1True);
                // 真值路径 = C1 真(c1)或 C2 假(!c2)→ return c1 || !c2
                combined = new BinExpr(BinaryOperator.OR,
                        cond, AstCleanup.negateCond(nestedCond));
            } else {
                return null;
            }
        } else {
            // 嵌套条件在 C1 真路径(少见布局):共享目标 = c1False
            if (c1False == c2True) {
                // && 变体:真值在 C2 假路径 → return c1 && !c2
                if (!blockEndsWithBooleanValue(c2False, true, ir)) {
                    return null;
                }
                consume.add(c2False);
                valueBlocks.add(c2False);
                valueBlocks.add(c1False);
                combined = new BinExpr(BinaryOperator.AND,
                        cond, AstCleanup.negateCond(nestedCond));
            } else if (c1False == c2False) {
                // || 变体:假值在 C2 真路径 → return c1 || c2
                if (!blockEndsWithBooleanValue(c2True, false, ir)) {
                    return null;
                }
                consume.add(c2True);
                valueBlocks.add(c2True);
                valueBlocks.add(c1False);
                combined = new BinExpr(BinaryOperator.OR, cond, nestedCond);
            } else {
                return null;
            }
        }

        // 值块在合并块(follow)汇合为 RETURN←PHI(方法直接返回布尔)
        // 或 STORE←PHI(布尔结果存入变量,后续使用,如 boolean r = a && b).
        // 仅从值块求公共后继(consume 含双后继的 C2 会干扰).
        BasicBlock merge = commonSuccessorOf(valueBlocks, graph);
        // STORE 形态:registerPhiReplacement 使 follow 的 STORE r←PHI 还原为
        // r = (a OP b),声明语句由 follow 组生成——故不消费合并块,
        // 否则 r 声明被吞,后续引用 r 未声明.
        if (merge != null) {
            for (IrInstruction fi : ir.instructionsOf(merge)) {
                if (fi.opcode() == IrOpcode.STORE && fi.operands().size() >= 2
                        && fi.operands().get(0) instanceof Variable sv
                        && fi.operands().get(1) instanceof InstructionRef ref
                        && ref.instruction().opcode() == IrOpcode.PHI) {
                    ops.registerPhiReplacement(ref.instruction().id(), combined);
                    // 消费菱形(不含合并块),返回头组前置语句
                    for (BlockGroup g : groups) {
                        if (consumed.contains(g)) {
                            continue;
                        }
                        for (BasicBlock b : g.blocks()) {
                            if (consume.contains(b)) {
                                consumed.add(g);
                                break;
                            }
                        }
                    }
                    return preIfStmts.isEmpty() ? null : new BlockStatement(preIfStmts);
                }
            }
        }
        // RETURN 形态:直接返回 return (a OP b).仅限 boolean 返回值方法——
        // 否则(如 boolean r = a && b 后接 (r?1:0)*10)会把布尔直接 return,
        // 与 int 返回类型不匹配.
        boolean isBoolRet = ir.method().returnType() != null
                && ir.method().returnType().kind() == TypeKind.BOOLEAN;
        if (!isBoolRet || merge == null) {
            return null;
        }
        consume.add(merge);
        for (BlockGroup g : groups) {
            if (consumed.contains(g)) {
                continue;
            }
            for (BasicBlock b : g.blocks()) {
                if (consume.contains(b)) {
                    consumed.add(g);
                    break;
                }
            }
        }
        Statement ret = new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(combined);
        if (!preIfStmts.isEmpty()) {
            List<Statement> all = new ArrayList<>(preIfStmts);
            all.add(ret);
            return new BlockStatement(all);
        }
        return ret;
    }

    /** 计算集合中所有块的公共后继(短路值合并块). */
    private static BasicBlock commonSuccessorOf(Set<BasicBlock> blocks, ControlFlowGraph graph) {
        BasicBlock common = null;
        for (BasicBlock b : blocks) {
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() == EdgeKind.EXCEPTION || e.target() == graph.exitBlock()) {
                    continue;
                }
                BasicBlock t = e.target();
                if (t == b) {
                    continue;
                }
                if (common == null) {
                    common = t;
                } else if (common != t) {
                    return null;
                }
            }
        }
        return common;
    }

    /** 块是否含 CONDITION 指令(即嵌套条件头). */
    private static boolean isConditionBlock(BasicBlock b, LinearIr ir) {
        for (IrInstruction i : ir.instructionsOf(b)) {
            if (i.opcode() == IrOpcode.CONDITION) {
                return true;
            }
        }
        return false;
    }

    /** 块是否产生布尔常量 value:以 CONST 0/1 结尾(值压栈,合并块消费),
     *  以 RETURN 布尔常量结尾,或以 STORE v = 布尔常量 结尾(赋值形态). */
    private static boolean blockEndsWithBooleanValue(BasicBlock b, boolean value, LinearIr ir) {
        List<IrInstruction> insns = ir.instructionsOf(b);
        for (int i = insns.size() - 1; i >= 0; i--) {
            IrInstruction insn = insns.get(i);
            if (insn.opcode() == IrOpcode.RETURN && !insn.operands().isEmpty()) {
                Number n = booleanConst(insn.operands().getFirst());
                if (n != null) {
                    return (n.intValue() != 0) == value;
                }
            }
            if (insn.opcode() == IrOpcode.CONST && !insn.operands().isEmpty()) {
                Number n = booleanConst(insn.operands().getFirst());
                if (n != null) {
                    return (n.intValue() != 0) == value;
                }
            }
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2) {
                Number n = booleanConst(insn.operands().get(1));
                if (n != null) {
                    return (n.intValue() != 0) == value;
                }
            }
            if (StatementUtils.isStatementRoot(insn)) {
                break; // 遇到其他语句,该块不是纯布尔值块
            }
        }
        return false;
    }

    /** 从 Value 中提取整型常量(支持 ConstantValue 与 CONST 指令引用). */
    private static Number booleanConst(Value v) {
        if (v instanceof com.bingbaihanji.bdec.ir.ConstantValue cv
                && cv.value() instanceof Number num) {
            return num;
        }
        if (v instanceof InstructionRef ref && ref.instruction().opcode() == IrOpcode.CONST
                && !ref.instruction().operands().isEmpty()
                && ref.instruction().operands().getFirst() instanceof com.bingbaihanji.bdec.ir.ConstantValue cv
                && cv.value() instanceof Number num) {
            return num;
        }
        return null;
    }

    /**
     * 判断分支体是否为空或仅由单个表达式语句构成,其值汇入合并点的 PHI.
     *
     * <p>用于三元折叠:常量分支(如 {@code b ? 1 : 2})的分支体为空块,
     * 方法调用分支(如 {@code b ? foo() : bar()})的分支体为单个表达式语句
     * ——该表达式的返回值经 {@code goto} 汇入合并点的 PHI,同样应折叠为三元,
     * 而不能当作独立语句输出(否则 {@code foo()} 被丢弃,{@code int y = foo()}
     * 无条件重复求值,语义错误).</p>
     */
    private static boolean isTernaryShaped(Statement body) {
        if (body == null) {
            return true;
        }
        Statement flat = body;
        while (flat instanceof BlockStatement bs && bs.statements().size() == 1) {
            flat = bs.statements().getFirst();
        }
        if (flat instanceof BlockStatement bs) {
            return bs.statements().isEmpty();
        }
        if (flat instanceof com.bingbaihanji.bdec.ast.stmt.ExpressionStatement) {
            return true;
        }
        // 嵌套三元:分支体本身是 if-else,其两个分支也都为"纯值形状".
        if (flat instanceof IfStatement ifs) {
            return isTernaryShaped(ifs.thenBranch()) && isTernaryShaped(ifs.elseBranch());
        }
        return false;
    }

}
