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


    /** 翻译循环体内的一个区域(从 start 出发,止于 latch 与循环出口) */
    static List<Statement> translateLoopRegion(ReducerOps ops, BasicBlock start, BasicBlock latch,
                                               Set<BasicBlock> body,
                                               Set<BasicBlock> exitTargets,
                                               List<BlockGroup> allGroups, LinearIr ir,
                                               Set<BlockGroup> consumed,
                                               ControlFlowGraph graph) {
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
                    continue; // 止于 latch 或循环出口
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
                    return wrapLoopStatement(ops, nestedLoop, nestedBody,
                            ops.extractConditionFromHeader(nestedLoop.header(), ir));
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

        if (fLatch && !tExit) {
            // FALSE 分支是 continue:if (!cond) continue; + TRUE 区域
            result.add(new IfStatement(AstCleanup.negateCond(cond), StatementUtils.continueStmt(), null));
            if (!tLatch) {
                result.addAll(translateLoopRegion(ops, trueTarget, latch, body, exitTargets,
                        allGroups, ir, consumed, graph));
            }
            return StatementUtils.blockOf(result);
        }
        if (tLatch && !fExit) {
            // TRUE 分支是 continue:if (cond) continue; + FALSE 区域
            result.add(new IfStatement(cond, StatementUtils.continueStmt(), null));
            if (!fLatch) {
                result.addAll(translateLoopRegion(ops, falseTarget, latch, body, exitTargets,
                        allGroups, ir, consumed, graph));
            }
            return StatementUtils.blockOf(result);
        }
        if (fExit && !tExit) {
            // FALSE 分支是 break:if (!cond) break; + TRUE 区域
            result.add(new IfStatement(AstCleanup.negateCond(cond),
                    new com.bingbaihanji.bdec.ast.stmt.BreakStatement(), null));
            if (!tLatch) {
                result.addAll(translateLoopRegion(ops, trueTarget, latch, body, exitTargets,
                        allGroups, ir, consumed, graph));
            }
            return StatementUtils.blockOf(result);
        }
        if (tExit && !fExit) {
            // TRUE 分支是 break:if (cond) break; + FALSE 区域
            result.add(new IfStatement(cond,
                    new com.bingbaihanji.bdec.ast.stmt.BreakStatement(), null));
            if (!fLatch) {
                result.addAll(translateLoopRegion(ops, falseTarget, latch, body, exitTargets,
                        allGroups, ir, consumed, graph));
            }
            return StatementUtils.blockOf(result);
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
