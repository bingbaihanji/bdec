package com.bingbaihanji.bdec.structuring;

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
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
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

}
