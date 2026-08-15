package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * try-catch 翻译器——从 {@link BlockReducer} 中按 Vineflower
 * "每模式一处理器"风格提取的 try/synchronized 专用翻译逻辑.
 *
 * <p>包含:try 区域包装({@link #wrapTryStatements} 按组区间映射语句区间,
 * 分支体内 try 区域收集 {@link #collectBranchTryAnns}),finally 合并
 * ({@link #buildTryCatch} 剥离重复 finally 体),synchronized 块保护
 * ({@link #wrapSynchronized}/{@link #collectSyncBody} 与 {@link #isSynchronizedHandler}
 * 识别).依赖归约状态的能力(组翻译,处理器指令翻译)通过 {@link ReducerOps}
 * 回调 {@link BlockReducer},本类保持无状态.</p>
 */
public final class TryTranslator {

    private TryTranslator() {}

    /**
     * 后处理:根据 CFG 异常范围将语句组包装为 try-catch 结构.
     * 在 if/else/loop 结构化之后运行,以确保嵌套控制结构得以保留.
     *
     * <p>核心思路:追踪哪些原始基本块属于每个 try 范围,
     * 将最终语句重新组装回对应的组中,找出所有基本块均位于 try 范围内的组,
     * 并仅包装这些组.
     */
    static BlockStatement wrapTryCatchBlocks(ReducerOps ops,
                                             BlockStatement root,
                                             List<Integer> stmtGroupIdxSrc,
                                             List<BlockGroup> groups,
                                             List<TryCatchInfo> tryCatchAnns,
                                             LinearIr ir) {
        if (tryCatchAnns.isEmpty()) {
            return root;
        }
        List<Statement> stmts = new ArrayList<>(root.statements());
        List<Statement> wrapped = wrapTryStatements(ops, stmts, stmtGroupIdxSrc, groups,
                tryCatchAnns, ir);
        return new BlockStatement(wrapped);
    }

    /**
     * 将 try 区域包装为 TryStatement.
     * stmtGroupIdx[i] 记录 stmts[i] 由哪个 BlockGroup 翻译而来,
     * 使 try 范围的组区间可以精确映射到语句区间(分支体内的 try
     * 与顶层 try 共用同一算法,不再依赖"语句索引 == 组索引"的脆弱假设).
     */
    static List<Statement> wrapTryStatements(ReducerOps ops,
                                             List<Statement> stmts,
                                             List<Integer> stmtGroupIdx,
                                             List<BlockGroup> groups,
                                             List<TryCatchInfo> tryCatchAnns,
                                             LinearIr ir) {
        if (tryCatchAnns.isEmpty() || stmts.isEmpty()) {
            return stmts;
        }

        List<Statement> out = new ArrayList<>(stmts);

        // 按处理器合并 tci:javac 为 finally 复制会将 try 区域按分支拆分
        // 为多个异常范围(同一处理器),分别包装会产生嵌套 try-finally
        // 和重复的 finally 执行.合并后以 tryBlocks 并集一次性包装.
        List<TryCatchInfo> mergedTcis = new ArrayList<>();
        for (TryCatchInfo tci : tryCatchAnns) {
            boolean mergedIntoExisting = false;
            for (int mi = 0; mi < mergedTcis.size(); mi++) {
                TryCatchInfo existing = mergedTcis.get(mi);
                if (java.util.Objects.equals(existing.handlerBlock(), tci.handlerBlock())) {
                    Set<BasicBlock> union = new HashSet<>(existing.tryBlocks());
                    union.addAll(tci.tryBlocks());
                    mergedTcis.set(mi, new TryCatchInfo(union, existing.handlerBlock(),
                            mergeCatchTypes(existing.catchType(), tci.catchType()),
                            Math.min(existing.startPc(), tci.startPc()),
                            Math.max(existing.endPc(), tci.endPc())));
                    mergedIntoExisting = true;
                    break;
                }
            }
            if (!mergedIntoExisting) {
                mergedTcis.add(tci);
            }
        }

        // 对每个 try-catch 注解,找出所有基本块均在 try 范围内的连续组
        for (TryCatchInfo tci : mergedTcis) {

            // 跳过去糖化产生的内部机制处理器(try-with-resources 的
            // close 清理链):整个 try-handler 对嵌套在另一个 try 区域内
            //(处理器块与 tryBlocks 都在另一区域的 tryBlocks 中),
            // 不是源码层面的独立 catch/finally,包装会产生嵌套垃圾结构.
            // 注意:仅处理器块被覆盖而 tryBlocks 独立的情形
            //(如 catch 体本身受外层 finally 保护)不是内部机制,不能跳过.
            boolean handlerIsInternal = false;
            // 内部机制判定仅适用于 catch-all/null(如 try-with-resources 的 close 链).
            // 特定 catchType 的<真实 catch>不能跳过——其 handler 在 finally 保护区,
            // tryBlocks 为子集时,若跳过会导致 catch 子句丢失(如
            // try{...}catch(ISE){...}finally{...} 的 ISE catch 被误当内部机制丢弃).
            if (tci.catchType() == null
                    || "java/lang/Throwable".equals(tci.catchType())) {
                for (var other : mergedTcis) {
                    if (other == tci) {
                        continue;
                    }
                    if (other.tryBlocks().contains(tci.handlerBlock())
                            && other.tryBlocks().containsAll(tci.tryBlocks())) {
                        handlerIsInternal = true;
                        break;
                    }
                }
            }
            if (handlerIsInternal) {
                continue;
            }

            // 找出所有基本块均在 try 范围内的组.
            // 注意:try 组在支配树序中可能被处理器组等穿插,
            // 因此收集精确的组集合而非 [first..last] 区间.
            Set<Integer> tryGroupSet = new java.util.LinkedHashSet<>();
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
                    tryGroupSet.add(i);
                }
            }

            if (firstTryGroup < 0) {
                continue;
            }

            // 将 try 组集合映射到语句区间
            int firstStmt = -1;
            int lastStmt = -1;
            for (int i = 0; i < stmtGroupIdx.size() && i < out.size(); i++) {
                int gi = stmtGroupIdx.get(i);
                if (tryGroupSet.contains(gi)) {
                    if (firstStmt < 0) {
                        firstStmt = i;
                    }
                    lastStmt = i;
                }
            }
            if (firstStmt < 0) {
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
                    && !StatementUtils.handlerBlockContainsNew(tci.handlerBlock(), ir);

            // 收集正常退出块(finally 模式):从 try 区域沿非异常边 BFS,
            // 仅纳入所有非异常前驱都在区域内的块(纯正常退出链).
            // 例如 try { ... } finally { f() } 的正常路径复制块
            // (位于 endPc 处的 finally 体副本),遇到汇合点
            //(有区域外前驱的块)即停止.
            Set<BasicBlock> exitBlocks = new java.util.LinkedHashSet<>();
            if (isFinally) {
                ControlFlowGraph graph = ir.controlFlowGraph();
                Set<BasicBlock> region = new HashSet<>(tci.tryBlocks());
                Deque<BasicBlock> queue = new ArrayDeque<>(tci.tryBlocks());
                Set<BasicBlock> regionVisited = new HashSet<>(tci.tryBlocks());
                while (!queue.isEmpty()) {
                    BasicBlock curr = queue.poll();
                    for (var e : graph.outgoingOf(curr)) {
                        if (e.kind() == EdgeKind.EXCEPTION) {
                            continue;
                        }
                        BasicBlock t = e.target();
                        if (t == graph.exitBlock() || !regionVisited.add(t)) {
                            continue;
                        }
                        if (region.contains(t)) {
                            continue;
                        }
                        boolean allInRegion = true;
                        for (var pe : graph.incomingOf(t)) {
                            if (pe.kind() != EdgeKind.EXCEPTION
                                    && !region.contains(pe.source())) {
                                allInRegion = false;
                                break;
                            }
                        }
                        if (allInRegion) {
                            region.add(t);
                            exitBlocks.add(t);
                            queue.add(t);
                        }
                    }
                }
            }
            // 过滤退出块:仅纳入 finally 副本块 + 尾部返回块(tail return),
            // 排除 try 之后的后续代码.
            // 正常退出路径 = [finally 副本] [tail return 或 后续代码].
            // try { return X; } finally {...} 的 return X 值在 try 区准备(存临时变量),
            // 属于 try 体,应拉入;而 try {...} finally {...} return X; 的 return X
            // 值在退出块计算(getfield 等),属 try 之后的代码,不应拉入 try 体.
            Set<BasicBlock> pullInExitBlocks = new java.util.LinkedHashSet<>();
            // 预收集 handler 区的指令操作码序列,用于识别 finally 副本块:
            // finally 副本在正常退出路径与 handler 中都会执行,操作码序列相同.
            Set<List<IrOpcode>> handlerOpcodeSeq =
                    collectHandlerOpcodeSequences(tci.handlerBlock(), ir, ir.controlFlowGraph());
            for (BasicBlock b : exitBlocks) {
                boolean hasReturn = false;
                for (IrInstruction insn : ir.instructionsOf(b)) {
                    if (insn.opcode() == IrOpcode.RETURN) {
                        hasReturn = true;
                        break;
                    }
                }
                if (hasReturn) {
                    if (isTailReturn(b, ir, tci.tryBlocks())) {
                        pullInExitBlocks.add(b);
                    }
                    break; // 返回块及其后不再拉入 try 体
                }
                // 仅拉入 finally 副本块(操作码序列与 handler 区一致).
                // 非 finally 副本的退出块(如 return 值计算块:concat/字段加载)属
                // try 之后代码——若误拉入会把 return 一起带进 try 体(语义错误,
                // 如 try-with-resources 的 return 被拉到资源 close 之前求值).
                if (!isFinallyCopy(b, handlerOpcodeSeq, ir)) {
                    break;
                }
                pullInExitBlocks.add(b); // finally 副本块(无 return),继续
            }
            exitBlocks = pullInExitBlocks;

            Set<Integer> exitGroupSet = new java.util.LinkedHashSet<>();
            if (!exitBlocks.isEmpty()) {
                for (int i = 0; i < groups.size(); i++) {
                    boolean allExit = !groups.get(i).blocks().isEmpty();
                    for (BasicBlock b : groups.get(i).blocks()) {
                        if (!exitBlocks.contains(b)) {
                            allExit = false;
                            break;
                        }
                    }
                    if (allExit) {
                        exitGroupSet.add(i);
                    }
                }
            }
            int lastStmtEnd = lastStmt;
            for (int i = lastStmt + 1; i < stmtGroupIdx.size() && i < out.size(); i++) {
                int gi = stmtGroupIdx.get(i);
                if (exitGroupSet.contains(gi)) {
                    lastStmtEnd = i;
                }
            }
            lastStmt = lastStmtEnd;

            // 构建 try 体:从 firstStmt 到 lastStmt 的语句
            List<Statement> tryBodyStmts = new ArrayList<>();
            for (int i = firstStmt; i <= lastStmt && i < out.size(); i++) {
                tryBodyStmts.add(out.get(i));
            }

            if (!tryBodyStmts.isEmpty()) {
                Statement tryBody = tryBodyStmts.size() == 1
                        ? tryBodyStmts.get(0)
                        : new BlockStatement(tryBodyStmts);

                // 跳过 synchronized 块的 try-catch 包装——
                // 异常处理器是 JVM 伪影(monitorexit 重试),
                // 而非真正的 Java 源码 catch/finally.
                if (AstCleanup.containsSynchronizedStatement(tryBody)) {
                    continue;
                }

                // 跳过 MatchException 模式匹配处理器的 try-catch 包装.
                if (isMatchExceptionHandler(tci, ir)) {
                    continue;
                }

                // 检测 synchronized 块模式:try 体包含 MONITOR_ENTER,
                // 处理器执行 MONITOR_EXIT + THROW.
                // 直接生成 SynchronizedStatement 而非 try-finally.
                if (SynchronizedTranslator.isSynchronizedHandler(tci, ir)) {
                    String monObj = SynchronizedTranslator.extractMonitorObject(tci, ir);
                    SynchronizedStatement syncStmt = new SynchronizedStatement(
                            new VarExpr(monObj), tryBody);
                    // 从方法体中剥离 synchronized 前导代码(DUP/ASTORE)
                    syncStmt = AstCleanup.stripSyncPreamble(syncStmt);
                    out.set(firstStmt, syncStmt);
                } else {
                    out.set(firstStmt, buildTryCatch(ops, tci, tryBody, ir));
                }
                // 移除已被吸收的语句
                for (int i = lastStmt; i > firstStmt; i--) {
                    if (i < out.size()) {
                        out.remove(i);
                    }
                }
            }
        }

        return out;
    }

    /**
     * 收集分支体内应包装的 try 区域:仅处理 tryBlocks 完全包含在分支内
     * 的区域——若 try 区域跨越整个 if/else(如 lock+try-finally 包裹分支体),
     * 由顶层包装处理,此处包装会产生双重 finally(unlock 两次).
     */
    static List<TryCatchInfo> collectBranchTryAnns(Set<BasicBlock> branchBlocks,
                                                   List<TryCatchInfo> tryCatchAnns,
                                                   LinearIr ir) {
        List<TryCatchInfo> branchTcis = new ArrayList<>();
        for (TryCatchInfo t : tryCatchAnns) {
            // 分支包含 try 区域的入口块(最小偏移)时才在此包装.
            // 使用入口块而非全部 tryBlocks——try 区域可能包含
            // 仅经异常边可达的处理器片段,它们不属于任何正常分支.
            BasicBlock entry = t.tryBlocks().stream()
                    .min(java.util.Comparator.comparingInt(BasicBlock::startOffset))
                    .orElse(null);
            if (entry == null || !branchBlocks.contains(entry)) {
                continue;
            }
            // 与分支外的正常路径区域共享同一处理器(javac 按分支
            // 拆分 finally 范围):由外层包装处理,此处包装会产生双重 finally.
            // 仅统计正常路径范围——纯处理器范围(如 catch 体受 finally
            // 保护)不属于此模式,不应阻止分支内包装.
            boolean sharedHandlerOutside = false;
            for (TryCatchInfo other : tryCatchAnns) {
                if (other == t
                        || !java.util.Objects.equals(other.handlerBlock(), t.handlerBlock())
                        || branchBlocks.containsAll(other.tryBlocks())) {
                    continue;
                }
                boolean otherIsHandlerOnly = other.tryBlocks().stream()
                        .anyMatch(b -> ir.controlFlowGraph().incomingOf(b).stream()
                                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION));
                if (!otherIsHandlerOnly) {
                    sharedHandlerOutside = true;
                    break;
                }
            }
            if (!sharedHandlerOutside) {
                branchTcis.add(t);
            }
        }
        return branchTcis;
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
    static boolean isMatchExceptionHandler(TryCatchInfo info, LinearIr ir) {
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

    /** 构建一个覆盖所有处理器块的 BlockGroup,沿 fallthrough 链从初始处理器块开始追踪 */
    static BlockGroup buildHandlerBlockGroup(TryCatchInfo info, LinearIr ir) {
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
            // 停止于汇合点:后继有处理器链之外的非异常前驱
            //(如 try 的正常退出路径),说明已到达 try 的 follow 块,
            // 继续追踪会把后续无关代码(含 NEW 指令)误判为处理器体.
            boolean isJoin = false;
            for (var pe : cfg.incomingOf(next)) {
                if (pe.kind() != EdgeKind.EXCEPTION && !visited.contains(pe.source())) {
                    isJoin = true;
                    break;
                }
            }
            // 重抛终点(try-with-resources 的 throw t 共享块)仍是处理器的一部分,
            // 不能当作汇合点提前停止,否则重抛丢失,异常被吞.
            if (isJoin && !isRethrowTerminal(next, ir)) {
                break;
            }
            group.add(next);
            current = next;
        }
        return group;
    }

    /** 判断块是否为重抛终点(以 THROW 指令结尾). */
    private static boolean isRethrowTerminal(BasicBlock block, LinearIr ir) {
        List<IrInstruction> insns = ir.instructionsOf(block);
        return !insns.isEmpty() && insns.getLast().opcode() == IrOpcode.THROW;
    }

    /** 收集处理器的所有 IR 指令,沿 fallthrough 链追踪.
     *  当 CFG 因自引用异常边而将处理器块分割时使用此方法. */
    static List<IrInstruction> collectHandlerInstructions(TryCatchInfo info, LinearIr ir) {
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
            // 停止于汇合点(与 buildHandlerBlockGroup 同理);但重抛终点仍是处理器的一部分
            boolean isJoin = false;
            for (var pe : cfg.incomingOf(next)) {
                if (pe.kind() != EdgeKind.EXCEPTION && !visited.contains(pe.source())) {
                    isJoin = true;
                    break;
                }
            }
            if (isJoin && !isRethrowTerminal(next, ir)) {
                break;
            }
            current = next;
        }
        return result;
    }

    /**
     * 根据 try-catch 信息构建 TryStatement.
     *
     * <p>检测 finally 块:当处理器为 catch-all(null 或 Throwable)
     * 且以 THROW 结尾(重新抛出模式)时,提取处理器体中除去 throw 之外的部分作为 finally 块.
     */
    static TryStatement buildTryCatch(ReducerOps ops, TryCatchInfo info, Statement tryBody, LinearIr ir) {
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
                && !StatementUtils.containsNewInstruction(handlerInsns);

        if (isFinally) {
            // 提取 finally 体:所有处理器指令去除最后的 THROW.
            // 构建一个跨越所有处理器块的合成 BlockGroup 用于翻译.
            Statement finallyBody = ops.translateHandlerWithoutThrow(info, ir, handlerInsns);

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
            tryBody = AstCleanup.stripDuplicatedFinally(tryBody, finallyBody);
            // 若 try 体被完全剥离(所有语句都与 finally 重复),
            // 使用空块而非 null——null 会在 AST 重写阶段引发 NPE.
            if (tryBody == null) {
                tryBody = new BlockStatement(List.of());
            }

            return new TryStatement(tryBody, List.of(), finallyBody);
        }

        // 常规 catch 子句
        List<TryStatement.CatchClause> catchClauses = new ArrayList<>();
        String excType = shortCatchType(info.catchType());
        // 若处理器创建了新的异常对象(如 record 模式匹配的 MatchException),
        // 则这是编译器生成的基础设施,而非用户代码.
        // 此时生成最小化的空 catch 体以保持代码可编译,
        // 而非尝试翻译包含无作用域变量的原始处理器指令.
        Statement handlerBody;
        if (StatementUtils.containsNewInstruction(handlerInsns)) {
            // 编译器生成的 record 模式匹配处理器——用简单的 throw e 保持可编译
            handlerBody = new BlockStatement(List.of(
                    new com.bingbaihanji.bdec.ast.stmt.ThrowStatement(
                            new VarExpr("e"))));
        } else {
            // 用户编写的 catch 子句——沿处理器链翻译(而非仅翻译首块):
            // catch 体的语句可能分布在多个块中(如拼接+println 分属两块),
            // 链遍历在汇合点停止,不会吞入 try 之后的代码.
            BlockGroup handlerGroup = buildHandlerBlockGroup(info, ir);
            handlerBody = ops.translateGroup(handlerGroup, ir);
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
     * 合并两个捕获类型:相同时保持单一;不同时(多 catch 联合类型,如
     * {@code catch (IOException | RuntimeException e)})以 "|" 连接内部名称.
     *
     * <p>javac 为同一 try 区域,同一处理器块发送多个异常表项,每项一个
     * catchType;反编译器须将其合并为单一 union 类型,而非丢到只剩一个.</p>
     */
    private static String mergeCatchTypes(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null || a.equals(b)) {
            return a;
        }
        return a + "|" + b;
    }

    /**
     * 将 catchType(内部名称,可能以 "|" 连接多 catch 联合)转为源码短名,
     * 多类型以 " | " 连接(如 "IOException | RuntimeException").
     */
    private static String shortCatchType(String internal) {
        if (internal == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int start = 0;
        for (int i = 0; i <= internal.length(); i++) {
            if (i == internal.length() || internal.charAt(i) == '|') {
                String part = internal.substring(start, i);
                int slash = part.lastIndexOf('/');
                String shortName = slash >= 0 ? part.substring(slash + 1) : part;
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(shortName);
                start = i + 1;
            }
        }
        return sb.toString();
    }

    /**
     * 收集 handler 可达块的指令操作码序列(finally 副本识别用).
     *
     * <p>javac 将 finally 体复制两份:一份在正常退出路径,一份在异常处理器中.
     * 两者操作码序列相同(仅字节码偏移不同).正常退出路径中与 handler 区某块
     * 操作码一致的块即 finally 副本;其余退出块(return 值计算等)是 try 之后
     * 的代码,不应拉入 try 体.</p>
     */
    private static Set<List<IrOpcode>> collectHandlerOpcodeSequences(BasicBlock handler,
                                                                     LinearIr ir,
                                                                     ControlFlowGraph graph) {
        Set<List<IrOpcode>> patterns = new HashSet<>();
        Set<BasicBlock> visited = new HashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(handler);
        while (!queue.isEmpty()) {
            BasicBlock b = queue.poll();
            if (!visited.add(b)) {
                continue;
            }
            List<IrOpcode> ops = ir.instructionsOf(b).stream()
                    .map(IrInstruction::opcode).toList();
            if (!ops.isEmpty()) {
                patterns.add(ops);
            }
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() != EdgeKind.EXCEPTION && e.target() != graph.exitBlock()) {
                    queue.add(e.target());
                }
            }
        }
        return patterns;
    }

    /** 退出块是否为 finally 副本:其指令操作码序列是某 handler 块序列的连续子序列.
     *
     * <p>handler 块常把 finally 副本与异常保存/重抛合并为单个块
     * (如 {@code astore ex; ...finally体...; aload ex; athrow}),因此不能用
     * 整块序列相等匹配,须检查连续子序列.非 finally 副本的退出块(return 值
     * 计算等)在 handler 中无对应代码,子序列不匹配.</p> */
    private static boolean isFinallyCopy(BasicBlock b, Set<List<IrOpcode>> handlerPatterns,
                                         LinearIr ir) {
        List<IrOpcode> ops = ir.instructionsOf(b).stream()
                .map(IrInstruction::opcode).toList();
        if (ops.isEmpty()) {
            return false;
        }
        for (List<IrOpcode> hp : handlerPatterns) {
            if (isContiguousSubsequence(ops, hp)) {
                return true;
            }
        }
        return false;
    }

    /** needle 是否为 haystack 的连续子序列. */
    private static boolean isContiguousSubsequence(List<IrOpcode> needle, List<IrOpcode> haystack) {
        if (needle.size() > haystack.size()) {
            return false;
        }
        outer:
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            for (int j = 0; j < needle.size(); j++) {
                if (needle.get(j) != haystack.get(i + j)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 判断退出块是否为尾部返回(tail return):RETURN 的值是 try 区内
     * STORE 的临时变量.
     *
     * <p>{@code try { return X; } finally {...}} 中,X 在 try 区被求值并存入
     * 临时变量,退出块的 RETURN 直接返回该临时变量(应保留在 try 体内,
     * 使 X 在 finally 之前求值).而 {@code try {...} finally {...} return X;}
     * 中,return X 的值在退出块计算(字段加载/表达式),属 try 之后的代码,
     * 不应拉入 try 体.</p>
     */
    private static boolean isTailReturn(BasicBlock block, LinearIr ir, Set<BasicBlock> tryRegion) {
        for (IrInstruction insn : ir.instructionsOf(block)) {
            if (insn.opcode() != IrOpcode.RETURN) {
                continue;
            }
            if (insn.operands().isEmpty()) {
                return false; // void return,非 tail return
            }
            Value v = insn.operands().get(0);
            if (!(v instanceof Variable var)) {
                return false; // 返回计算值(字段加载/表达式),非 try 区临时变量
            }
            // 该变量是否在 try 区内被 STORE(即 return 值在 try 区准备)
            for (BasicBlock rb : tryRegion) {
                for (IrInstruction ri : ir.instructionsOf(rb)) {
                    if (ri.opcode() == IrOpcode.STORE && storesTo(ri, var)) {
                        return true;
                    }
                }
            }
            return false;
        }
        return false; // 块内无 RETURN
    }

    /** STORE 指令的目标变量是否为 var(按 slot+version 匹配). */
    private static boolean storesTo(IrInstruction insn, Variable var) {
        if (insn.opcode() != IrOpcode.STORE || insn.operands().isEmpty()) {
            return false;
        }
        Value target = insn.operands().get(0);
        return target instanceof Variable sv
                && sv.slot() == var.slot() && sv.version() == var.version();
    }

}
