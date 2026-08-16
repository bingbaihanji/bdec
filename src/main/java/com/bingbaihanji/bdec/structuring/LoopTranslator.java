package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 循环语句翻译器——从 {@link BlockReducer} 中按 Vineflower
 * "每模式一处理器"风格提取的循环专用翻译逻辑.
 *
 * <p>包含:未折叠循环体的结构化翻译({@link #translateLoopBodyStructured}
 * 按体内出边分类构建 continue/break),do-while/while 包装
 * ({@link #wrapLoopStatement} 含条件引用声明提升)等.
 * 依赖归约状态的能力(组翻译,条件提取,后置自增挂起值)
 * 通过 {@link ReducerOps} 回调 {@link BlockReducer}.</p>
 */
public final class LoopTranslator {

    private LoopTranslator() {}

    /** 检查循环头块是否以条件跳转结尾(用于区分 do-while 与 while).
     *  <p>头块以条件跳转结尾说明测试在顶部(while 风格);
     *  否则条件是体末尾的回边(do-while 风格).</p> */
    static boolean headerEndsWithConditionalJump(BasicBlock header) {
        return header.endsWithConditionalJump();
    }


    /** 翻译循环体内的一个区域(从 start 出发,止于 stop/latch 与循环出口) */
    static List<Statement> translateLoopRegion(ReducerOps ops, BasicBlock start, BasicBlock latch,
                                               Set<BasicBlock> body,
                                               Set<BasicBlock> exitTargets,
                                               List<BlockGroup> allGroups, LinearIr ir,
                                               Set<BlockGroup> consumed,
                                               ControlFlowGraph graph) {
        return translateLoopRegion(ops, start, latch, body, exitTargets, allGroups, ir,
                consumed, graph, null);
    }

    /** 翻译循环体内的一个区域(从 start 出发,止于 stop/latch 与循环出口;
     *  stop 为额外边界,如 skip 分支汇入 true 目标时边界设为该目标). */
    static List<Statement> translateLoopRegion(ReducerOps ops, BasicBlock start, BasicBlock latch,
                                               Set<BasicBlock> body,
                                               Set<BasicBlock> exitTargets,
                                               List<BlockGroup> allGroups, LinearIr ir,
                                               Set<BlockGroup> consumed,
                                               ControlFlowGraph graph,
                                               BasicBlock stop) {
        Set<BasicBlock> region = new LinkedHashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock cur = queue.poll();
            if (!region.add(cur)) {
                continue;
            }
            for (var e : graph.outgoingOf(cur)) {
                if (e.kind() == EdgeKind.EXCEPTION) {
                    continue;
                }
                BasicBlock t = e.target();
                if (t == latch || t == stop || !body.contains(t)) {
                    continue; // 止于 stop/latch 或循环出口
                }
                queue.add(t);
            }
        }
        List<Statement> stmts = new ArrayList<>();
        for (BlockGroup g : allGroups) {
            if (consumed.contains(g)) {
                continue;
            }
            boolean inRegion = false;
            for (BasicBlock b : g.blocks()) {
                if (region.contains(b)) {
                    inRegion = true;
                    break;
                }
            }
            if (!inRegion) {
                continue;
            }
            consumed.add(g);
            Statement stmt = translateLoopBodyGroup(ops, g, latch, body, exitTargets,
                    allGroups, ir, consumed, graph);
            if (stmt != null) {
                stmts.add(stmt);
            }
        }
        return stmts;
    }


    /**
     * 结构化翻译未折叠的循环体(体内含 continue/break 等内部分支).
     *
     * <p>体内块的出边分类:
     * <ul>
     *   <li>仅通向 latch(纯 goto 链)→ continue</li>
     *   <li>仅通向循环体外(纯 goto 链)→ break</li>
     *   <li>其余 → 体内普通内容(递归翻译,可嵌套 if-else)</li>
     * </ul>
     * 条件块据此构建 {@code if (cond) continue;/break;} 并递归翻译
     * 另一分支的区域;latch 块(如 i++)正常翻译为循环体末尾语句.</p>
     */
    static Statement translateLoopBodyStructured(ReducerOps ops, LoopInfo loopInfo, List<BlockGroup> allGroups,
                                                 LinearIr ir, Set<BlockGroup> consumed,
                                                 ControlFlowGraph graph,
                                                 PostDominatorTree postDom) {
        Set<BasicBlock> body = loopInfo.body();
        BasicBlock header = loopInfo.header();
        BasicBlock latch = loopInfo.latches().isEmpty()
                ? header : loopInfo.latches().iterator().next();
        // 循环出口块:体内块的非异常后继中不在体内的块
        Set<BasicBlock> exitTargets = new HashSet<>();
        for (BasicBlock b : body) {
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() != EdgeKind.EXCEPTION && !body.contains(e.target())) {
                    exitTargets.add(e.target());
                }
            }
        }
        List<Statement> stmts = new ArrayList<>();
        for (BlockGroup g : allGroups) {
            if (consumed.contains(g)) {
                continue;
            }
            boolean inBody = false;
            for (BasicBlock b : g.blocks()) {
                if (body.contains(b) && b != header) {
                    inBody = true;
                    break;
                }
            }
            if (!inBody) {
                continue;
            }
            consumed.add(g);
            Statement stmt = translateLoopBodyGroup(ops, g, latch, body, exitTargets,
                    allGroups, ir, consumed, graph);
            if (stmt != null) {
                stmts.add(stmt);
            }
        }
        if (stmts.isEmpty()) {
            return new BlockStatement(List.of());
        }
        if (ops.pendingPostInc() != null) {
            stmts.add(ops.pendingPostInc());
            ops.setPendingPostInc(null);
        }

        if (stmts.size() == 1) {
            return stmts.getFirst();
        }
        return new BlockStatement(stmts);
    }


    /** 翻译循环体内的单个组:条件块构建 if+continue/break,普通块常规翻译 */
    static Statement translateLoopBodyGroup(ReducerOps ops, BlockGroup g, BasicBlock latch,
                                            Set<BasicBlock> body,
                                            Set<BasicBlock> exitTargets,
                                            List<BlockGroup> allGroups, LinearIr ir,
                                            Set<BlockGroup> consumed,
                                            ControlFlowGraph graph) {
        BasicBlock last = g.last();
        // 嵌套循环:组是另一个循环的头——递归结构化内层循环体
        for (BasicBlock gb : g.blocks()) {
            LoopInfo nestedLoop = ops.loopAnnotation(gb);
            if (nestedLoop != null && nestedLoop.header() != null
                    && (latch == null || nestedLoop.header() != latch)) {
                Statement nestedBody = translateLoopBodyStructured(ops, nestedLoop, allGroups,
                        ir, consumed, graph, null);
                if (nestedBody != null && !StatementUtils.isEmptyBlock(nestedBody)) {
                    Statement nested = wrapLoopStatement(ops, nestedLoop, nestedBody,
                            ops.extractConditionFromHeader(nestedLoop.header(), ir));
                    // 嵌套循环正常退出 return 补发(同 reduce 路径)
                    Statement followRet = loopFollowReturn(ops, nestedLoop, ir,
                            allGroups, consumed, graph);
                    if (followRet != null) {
                        return new BlockStatement(List.of(nested, followRet));
                    }
                    return nested;
                }
            }
        }
        boolean hasCond = ir.instructionsOf(last).stream()
                .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
        if (!hasCond) {

            return ops.translateGroup(g, ir);
        }
        BasicBlock trueTarget = null;
        BasicBlock falseTarget = null;
        for (var e : graph.outgoingOf(last)) {
            if (e.kind() == EdgeKind.TRUE_BRANCH) {
                trueTarget = e.target();
            } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                falseTarget = e.target();
            }
        }
        if (trueTarget == null || falseTarget == null) {
            return ops.translateGroup(g, ir);
        }
        Expression cond = AstCleanup.simplifyCondition(ops.extractConditionFromHeader(last, ir));
        List<Statement> pre = ops.translateHeaderNonCondition(g, ir);

        boolean tLatch = leadsOnlyTo(trueTarget, latch, body, graph, ir, new HashSet<>());
        boolean fLatch = leadsOnlyTo(falseTarget, latch, body, graph, ir, new HashSet<>());
        boolean tExit = leadsOnlyToExit(trueTarget, body, exitTargets, graph, ir, new HashSet<>());
        boolean fExit = leadsOnlyToExit(falseTarget, body, exitTargets, graph, ir, new HashSet<>());

        List<Statement> result = new ArrayList<>(pre);

        if (fLatch && !tLatch && !tExit
                && !leadsToPhiMerge(falseTarget, latch, body, graph, ir)) {
            // FALSE 分支是 continue:if (!cond) continue; + TRUE 区域.
            // 排除值路径:分支空块通向 latch 但 latch 有 PHI 合并(值三元汇入
            // 循环尾,如 builder.append(cond?A:B) 的 value 分支被折叠成空 goto),
            // 发射 continue 会错误跳过值消费——交给纯菱形抑制处理.
            result.add(new IfStatement(AstCleanup.negateCond(cond), StatementUtils.continueStmt(), null));
            result.addAll(translateLoopRegion(ops, trueTarget, latch, body, exitTargets,
                    allGroups, ir, consumed, graph));
            return StatementUtils.blockOf(result);
        }
        if (tLatch && !fLatch && !fExit
                && !leadsToPhiMerge(trueTarget, latch, body, graph, ir)) {
            // TRUE 分支是 continue:if (cond) continue; + FALSE 区域.
            result.add(new IfStatement(cond, StatementUtils.continueStmt(), null));
            result.addAll(translateLoopRegion(ops, falseTarget, latch, body, exitTargets,
                    allGroups, ir, consumed, graph));
            return StatementUtils.blockOf(result);
        }
        if (fExit && !tExit) {
            if (leadsOnlyToReturn(falseTarget, body, exitTargets, graph, ir, new HashSet<>())) {
                // FALSE 分支是方法返回:if (!cond) return <值>;(翻译区域产生 return)
                result.add(new IfStatement(AstCleanup.negateCond(cond),
                        StatementUtils.blockOf(translateLoopRegion(ops, falseTarget, latch,
                                body, exitTargets, allGroups, ir, consumed, graph)), null));
            } else {
                // FALSE 分支是 break:if (!cond) break; + TRUE 区域
                result.add(new IfStatement(AstCleanup.negateCond(cond),
                        new com.bingbaihanji.bdec.ast.stmt.BreakStatement(), null));
                if (!tLatch) {
                    result.addAll(translateLoopRegion(ops, trueTarget, latch, body, exitTargets,
                            allGroups, ir, consumed, graph));
                }
            }
            return StatementUtils.blockOf(result);
        }
        if (tExit && !fExit) {
            if (leadsOnlyToReturn(trueTarget, body, exitTargets, graph, ir, new HashSet<>())) {
                // TRUE 分支是方法返回:if (cond) return <值>;(for-each 早返回,
                // 如 for(e:list){if(...) return e;} —— 出口是方法 RETURN 而非 break)
                result.add(new IfStatement(cond,
                        StatementUtils.blockOf(translateLoopRegion(ops, trueTarget, latch,
                                body, exitTargets, allGroups, ir, consumed, graph)), null));
            } else {
                // TRUE 分支是 break:if (cond) break; + FALSE 区域
                result.add(new IfStatement(cond,
                        new com.bingbaihanji.bdec.ast.stmt.BreakStatement(), null));
                if (!fLatch) {
                    result.addAll(translateLoopRegion(ops, falseTarget, latch, body, exitTargets,
                            allGroups, ir, consumed, graph));
                }
            }
            return StatementUtils.blockOf(result);
        }
        // skip 分支模式:FALSE 分支汇入 TRUE 目标(分隔符等"跳过"分支,如
        // toString 循环体的 if(i<=0) 后接公共代码——FALSE 分支是 ", " 分隔符,
        // 汇入 TRUE 目标的公共代码).正确结构:
        //   if (!cond) { FALSE 分支 } + TRUE 目标区域(公共后续)
        // 否则普通 if-else 会把公共代码塞进 then,语义错误.
        if (!tLatch && !tExit && regionJoinsTarget(falseTarget, trueTarget, latch, body, graph)) {
            List<Statement> falseRegion = translateLoopRegion(ops, falseTarget, latch, body,
                    exitTargets, allGroups, ir, consumed, graph, trueTarget);
            if (!falseRegion.isEmpty()) {
                result.add(new IfStatement(AstCleanup.negateCond(cond),
                        StatementUtils.blockOf(falseRegion), null));
            }
            result.addAll(translateLoopRegion(ops, trueTarget, latch, body, exitTargets,
                    allGroups, ir, consumed, graph));
            return StatementUtils.blockOf(result);
        }
        // 纯三元菱形:两个分支都是无副作用的纯值计算(ldc/aload 等),汇入同一
        // 合并点,合并点的 PHI 被后续表达式消费(如 builder.append(cond ? A : B)
        // 的实参).不结构化 if——抑制空 if 让合并点继续,PHI 经 resolvePhiAsTernary
        // 还原为 CondExpr;否则一般 if-else 会把合并点(append 调用)吞进分支.
        if (!tLatch && !tExit && !fLatch && !fExit) {
            BasicBlock merge = commonMerge(trueTarget, falseTarget, latch, body, graph);
            // 合并点可为 latch(值三元汇入循环尾,如 builder.append(cond?A:B) 的
            // append 在 latch)——两分支都纯时抑制,真 continue(另一分支有代码)
            // 的纯分支为单个 continue,isPureBranch 会因另一分支非纯而不命中.
            if (merge != null
                    && isPureBranch(trueTarget, merge, body, graph, ir)
                    && isPureBranch(falseTarget, merge, body, graph, ir)) {
                return StatementUtils.blockOf(result);
            }
        }
        // 无 continue/break——体内普通 if-else,递归翻译两个分支
        Statement thenBody = StatementUtils.blockOf(translateLoopRegion(ops, trueTarget, latch, body, exitTargets,
                allGroups, ir, consumed, graph));
        Statement elseBody = StatementUtils.blockOf(translateLoopRegion(ops, falseTarget, latch, body, exitTargets,
                allGroups, ir, consumed, graph));
        result.add(new IfStatement(cond, thenBody, elseBody));
        return StatementUtils.blockOf(result);
    }


    /** 检查从 t 出发的所有非异常路径是否仅通向 latch(纯 goto 链,无语句内容) */
    static boolean leadsOnlyTo(BasicBlock t, BasicBlock latch, Set<BasicBlock> body,
                               ControlFlowGraph graph, LinearIr ir,
                               Set<BasicBlock> visited) {
        if (!visited.add(t)) {
            return true;
        }
        if (t == latch) {
            return true;
        }
        if (!body.contains(t)) {
            return false; // 离开循环体——不是 continue
        }
        // 桥接块必须无 IR 语句(纯 goto)
        if (!ir.instructionsOf(t).isEmpty()) {
            return false;
        }
        boolean any = false;
        for (var e : graph.outgoingOf(t)) {
            if (e.kind() == EdgeKind.EXCEPTION) {
                continue;
            }
            any = true;
            if (!leadsOnlyTo(e.target(), latch, body, graph, ir, visited)) {
                return false;
            }
        }
        return any; // 无后继(死块)视为 true
    }


    /** 检查从 t 出发的所有非异常路径是否仅通向循环出口(纯 goto 链,无语句内容) */
    static boolean leadsOnlyToExit(BasicBlock t, Set<BasicBlock> body,
                                   Set<BasicBlock> exitTargets, ControlFlowGraph graph,
                                   LinearIr ir, Set<BasicBlock> visited) {
        if (!visited.add(t)) {
            return true;
        }
        if (!body.contains(t) || exitTargets.contains(t)) {
            return true; // 已在循环体外
        }
        if (!ir.instructionsOf(t).isEmpty()) {
            return false;
        }
        boolean any = false;
        for (var e : graph.outgoingOf(t)) {
            if (e.kind() == EdgeKind.EXCEPTION) {
                continue;
            }
            any = true;
            if (!leadsOnlyToExit(e.target(), body, exitTargets, graph, ir, visited)) {
                return false;
            }
        }
        return any;
    }

    /** 从 t 出发的路径是否最终通向方法 RETURN(而非普通循环 break). */
    static boolean leadsOnlyToReturn(BasicBlock t, Set<BasicBlock> body,
                                     Set<BasicBlock> exitTargets, ControlFlowGraph graph,
                                     LinearIr ir, Set<BasicBlock> visited) {
        if (!visited.add(t)) {
            return true;
        }
        if (!body.contains(t) || exitTargets.contains(t)) {
            // 在循环体外:出口块含 RETURN 指令即方法返回
            return blockIsReturn(t, ir);
        }
        if (!ir.instructionsOf(t).isEmpty()) {
            return false;
        }
        boolean any = false;
        for (var e : graph.outgoingOf(t)) {
            if (e.kind() == EdgeKind.EXCEPTION) {
                continue;
            }
            any = true;
            if (!leadsOnlyToReturn(e.target(), body, exitTargets, graph, ir, visited)) {
                return false;
            }
        }
        return any;
    }

    /**
     * FALSE 分支区域(止于 stop/latch)是否存在指向 joinTarget 的边,
     * 即 FALSE 分支汇入 TRUE 目标(skip/分隔符模式).
     */
    private static boolean regionJoinsTarget(BasicBlock start, BasicBlock joinTarget,
                                             BasicBlock latch, Set<BasicBlock> body,
                                             ControlFlowGraph graph) {
        if (start == joinTarget || joinTarget == null) {
            return false;
        }
        Set<BasicBlock> region = new LinkedHashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock cur = queue.poll();
            if (!region.add(cur)) {
                continue;
            }
            for (var e : graph.outgoingOf(cur)) {
                if (e.kind() == EdgeKind.EXCEPTION) {
                    continue;
                }
                BasicBlock t = e.target();
                if (t == latch || !body.contains(t)) {
                    continue;
                }
                if (t == joinTarget) {
                    return true;
                }
                queue.add(t);
            }
        }
        return false;
    }

    /**
     * 两分支的公共合并点:从 trueTarget/falseTarget 出发(止于 latch),首个两分支
     * 都可达的块.用于纯三元菱形检测(分支纯值汇入同一合并点).
     */
    private static BasicBlock commonMerge(BasicBlock a, BasicBlock b,
                                          BasicBlock latch, Set<BasicBlock> body,
                                          ControlFlowGraph graph) {
        Set<BasicBlock> reachA = reachable(a, latch, body, graph);
        Set<BasicBlock> reachB = reachable(b, latch, body, graph);
        // 按 A 的遍历序找首个同时可达块(优先 A 直接目标)
        Deque<BasicBlock> queue = new ArrayDeque<>();
        Set<BasicBlock> seen = new HashSet<>();
        queue.add(a);
        while (!queue.isEmpty()) {
            BasicBlock cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (reachB.contains(cur) && cur != a) {
                return cur;
            }
            for (var e : graph.outgoingOf(cur)) {
                if (e.kind() == EdgeKind.EXCEPTION || e.target() == latch
                        || !body.contains(e.target())) {
                    continue;
                }
                queue.add(e.target());
            }
        }
        // 两分支都直达 latch(latch 是合并点,如值三元汇入循环尾)
        if (goesToLatch(a, latch, body, graph) && goesToLatch(b, latch, body, graph)) {
            return latch;
        }
        return null;
    }

    /** 区域(从 start 止于 latch)是否含指向 latch 的边. */
    private static boolean goesToLatch(BasicBlock start, BasicBlock latch,
                                       Set<BasicBlock> body, ControlFlowGraph graph) {
        Set<BasicBlock> region = reachable(start, latch, body, graph);
        for (BasicBlock rb : region) {
            for (var e : graph.outgoingOf(rb)) {
                if (e.kind() != EdgeKind.EXCEPTION && e.target() == latch) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 从 start 出发(止于 stop),可达的循环体块集合. */
    private static Set<BasicBlock> reachable(BasicBlock start, BasicBlock stop,
                                             Set<BasicBlock> body,
                                             ControlFlowGraph graph) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock cur = queue.poll();
            if (cur == stop || !result.add(cur)) {
                continue;
            }
            for (var e : graph.outgoingOf(cur)) {
                if (e.kind() == EdgeKind.EXCEPTION || e.target() == stop
                        || !body.contains(e.target())) {
                    continue;
                }
                queue.add(e.target());
            }
        }
        return result;
    }

    /** 分支区域(从 start 到 stop)是否全为无副作用指令(纯值计算). */
    private static boolean isPureBranch(BasicBlock start, BasicBlock stop,
                                        Set<BasicBlock> body,
                                        ControlFlowGraph graph, LinearIr ir) {
        Set<BasicBlock> region = reachable(start, stop, body, graph);
        for (BasicBlock rb : region) {
            for (IrInstruction i : ir.instructionsOf(rb)) {
                if (i.opcode() == IrOpcode.STORE
                        || i.opcode() == IrOpcode.INVOKE
                        || i.opcode() == IrOpcode.NEW
                        || i.opcode() == IrOpcode.NEW_ARRAY
                        || i.opcode() == IrOpcode.FIELD_STORE
                        || i.opcode() == IrOpcode.ARRAY_STORE
                        || i.opcode() == IrOpcode.MONITOR_ENTER
                        || i.opcode() == IrOpcode.MONITOR_EXIT
                        || i.opcode() == IrOpcode.THROW
                        || i.opcode() == IrOpcode.RETURN
                        || i.opcode() == IrOpcode.CONDITION) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 分支(空块→latch)是否通向含 PHI 的合并块(值路径而非 continue):
     * 值三元(如 builder.append(cond?A:B))把 A/B 推栈后空 goto 到 latch,
     * latch 的 PHI 合并两分支值;continue 分支则无值合并.
     */
    private static boolean leadsToPhiMerge(BasicBlock start, BasicBlock latch,
                                           Set<BasicBlock> body,
                                           ControlFlowGraph graph, LinearIr ir) {
        Set<BasicBlock> region = reachable(start, latch, body, graph);
        for (BasicBlock rb : region) {
            for (IrInstruction i : ir.instructionsOf(rb)) {
                if (i.opcode() == IrOpcode.PHI) {
                    return true;
                }
            }
        }
        // 也检查 latch 自身
        for (IrInstruction i : ir.instructionsOf(latch)) {
            if (i.opcode() == IrOpcode.PHI) {
                return true;
            }
        }
        return false;
    }

    /** 块中是否含 RETURN 指令(方法出口). */
    private static boolean blockIsReturn(BasicBlock b, LinearIr ir) {
        if (b == null) {
            return false;
        }
        // RETURN 或 THROW 都是方法终止分支:循环体内 if 的该分支应结构化
        // 为 if (!cond) return/throw(而非 if (cond) break + 循环后补 throw,
        // 后者把仅条件成立才执行的 throw 变成无条件执行,语义错误).
        return ir.instructionsOf(b).stream()
                .anyMatch(i -> i.opcode() == IrOpcode.RETURN
                        || i.opcode() == IrOpcode.THROW);
    }

    /**
     * 循环正常退出的补发 return:循环头条件不成立(while 的 FALSE 分支)通向的
     * 出口块若含 RETURN 且其组已被体内分支消费(同一出口块被体内 if 的 return
     * 复用),循环后的 fallthrough return 会丢失(如 indexOf 的
     * {@code while(i>=0){...}} 后缺 {@code return ~end})——此处补发.
     * 供 reduce() 与嵌套循环路径共用.
     *
     * @return 循环后应补发的 ReturnStatement;不需要补发返回 null
     */
    static Statement loopFollowReturn(ReducerOps ops, LoopInfo loopInfo, LinearIr ir,
                                      List<BlockGroup> groups, Set<BlockGroup> consumed,
                                      ControlFlowGraph graph) {
        BasicBlock header = loopInfo != null ? loopInfo.header() : null;
        if (header == null) {
            return null;
        }
        for (var e : graph.outgoingOf(header)) {
            if (e.kind() != EdgeKind.FALSE_BRANCH) {
                continue;
            }
            BasicBlock exit = e.target();
            if (loopInfo.body().contains(exit)) {
                continue;
            }
            // 仅当出口块组已被消费(否则 reduce() 会正常发射其后的 return)
            BlockGroup g = null;
            for (BlockGroup cg : groups) {
                if (cg.blocks().contains(exit)) {
                    g = cg;
                    break;
                }
            }
            if (g == null || !consumed.contains(g)) {
                continue;
            }
            boolean hasReturn = ir.instructionsOf(exit).stream()
                    .anyMatch(i -> i.opcode() == IrOpcode.RETURN);
            if (!hasReturn) {
                continue;
            }
            return ops.translateGroup(g, ir);
        }
        return null;
    }


    /** 将已翻译的循环体包装为 LoopStatement,统一 do-while/while 判定与条件取反.
     *  <p>reduce() 主循环与 translateBranchGroup(分支体内的循环)共用此逻辑.</p>
     *
     * @param loopInfo   循环注解
     * @param body       已翻译的循环体
     * @param rawCond    提取的原始条件(可为 null)
     * @return LoopStatement,或 body 本身(体为空时)
     */
    static Statement wrapLoopStatement(ReducerOps ops, LoopInfo loopInfo, Statement body, Expression rawCond) {
        if (body == null || StatementUtils.isEmptyBlock(body)) {
            return body;
        }
        Expression cond = AstCleanup.simplifyCondition(rawCond);
        // do-while 检测:回边从循环头直接指向自身(自环),
        // 或循环头不以条件跳转结尾(条件是体末尾的回边).
        // 此时字节码条件跳转本身就是"满足时继续"的语义,
        // 不需要取反.
        boolean isDoWhile = loopInfo.latches().contains(loopInfo.header())
                || !headerEndsWithConditionalJump(loopInfo.header());
        if (cond != null && !isDoWhile) {
            // bytecode 中的条件跳转(如 ifeq/iflt)表示"满足条件时跳转到循环出口".
            // 但 Java while 循环的条件表示"满足条件时继续循环".
            // 因此需要取反:while-loop 条件 = NOT(bytecode jump condition).
            // 例如 ifeq exit 表示 value==0 时退出,while 条件应为 value!=0.
            cond = new UnExpr(UnaryOperator.NOT, cond);
            cond = AstCleanup.simplifyCondition(cond);
        }
        {
            // 循环折叠可能把预置头块(STORE 初始化)并入循环体
            // (do-while 的入口块,while 的头部折叠).被条件引用的
            // 前导变量声明必须提升到循环外——声明在体内会与外部同名
            // 变量冲突(JLS 禁止局部变量遮蔽),且每次迭代重置初始值
            // 会改变语义.仅提升条件引用的声明:条件在体内声明之前
            // 求值,源码中不可能引用体内局部,这类声明必属预置头.
            // 真正的体内局部声明(如 while(c) { int x = f(); ... })
            // 不受影响.
            Statement[] hoisted = StatementUtils.hoistConditionReferencedDeclarations(body, cond);
            if (hoisted != null && hoisted[0] != null) {
                LoopStatement inner = new LoopStatement(
                        isDoWhile ? LoopStatement.LoopKind.DO_WHILE
                                : LoopStatement.LoopKind.WHILE,
                        cond != null ? cond : new VarExpr("true"), hoisted[1]);
                return new BlockStatement(List.of(hoisted[0], inner));
            }
        }
        return new LoopStatement(
                isDoWhile ? LoopStatement.LoopKind.DO_WHILE : LoopStatement.LoopKind.WHILE,
                cond != null ? cond : new VarExpr("true"), body);
    }


}
