package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * switch 语句翻译器——从 {@link BlockReducer} 中按 Vineflower
 * "每模式一处理器"风格提取的 switch 专用翻译逻辑.
 *
 * <p>包含:两级字符串 switch 的 case 区域结构化翻译
 * ({@link #translateSwitchCaseRegion} 按 CFG 边构造 case 体内的 if)、
 * switch 表达式的结果变量识别({@link #detectSwitchFollow})、
 * typeSwitch 守卫翻译({@link #translateTypeSwitchCase})等.
 * 所有依赖归约状态的能力(表达式翻译、块组翻译、作用域追踪、
 * PHI 分支上下文)通过 {@link ReducerOps} 回调 {@link BlockReducer},
 * 本类保持无状态.</p>
 */
public final class SwitchTranslator {

    private SwitchTranslator() {}

    /** 根据 switch 信息和分组块构建 SwitchStatement */
    static Statement buildSwitch(ReducerOps ops, SwitchInfo info, BlockGroup group, LinearIr ir,
                                        List<BlockGroup> allGroups, Set<BlockGroup> consumed,
                                        ControlFlowGraph graph) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        Expression discriminant = new VarExpr("switchKey");
        boolean isTypeSwitch = false;
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.SWITCH && !insn.operands().isEmpty()) {
                discriminant = ops.valueToExpr(insn.operands().getFirst());
                // typeSwitch 模式:判别式是 INDY 结果占位符.
                // 保留 switchKey(需要合成变量声明).
                if (discriminant instanceof VarExpr v && "switchKey".equals(v.name())) {
                    isTypeSwitch = true;
                }
                break;
            }
        }

        // 头块中 SWITCH 之前的语句(如字符串 switch 的
        // "String var2 = s; int var3 = -1;").必须在此翻译——
        // 先于 case 体,使变量名注册到作用域,case 体内的后续 STORE
        // 才会翻译为赋值而非重复声明.
        List<Statement> preSwitch = ops.translateHeaderNonCondition(group, ir);

        // typeSwitch:定位重启块(含 typeSwitch INDY 的块)与 switchKey 槽位.
        // 守卫失败时字节码更新索引并跳回该块重新调度,
        // 对应 Java 的 case 贯穿到下一 case.
        BasicBlock restartBlock = null;
        int switchKeySlot = -1;
        if (isTypeSwitch) {
            for (IrInstruction insn : allInsns) {
                if (insn.opcode() == IrOpcode.SWITCH && !insn.operands().isEmpty()
                        && insn.operands().getFirst() instanceof InstructionRef ref) {
                    IrInstruction indy = ref.instruction();
                    for (BasicBlock gb : graph.blocks()) {
                        if (gb.id() == indy.blockId()) {
                            restartBlock = gb;
                            break;
                        }
                    }
                    for (Value op : indy.operands()) {
                        if (op instanceof Variable v) {
                            switchKeySlot = v.slot();
                            break;
                        }
                    }
                    break;
                }
            }
        }

        // 收集所有 case 目标块,以便消费它们对应的组
        Set<BasicBlock> allCaseBlocks = new HashSet<>();
        info.caseBodies().values().forEach(allCaseBlocks::addAll);
        allCaseBlocks.addAll(info.defaultBody());

        // 检测 switch 表达式模式:所有 case 体汇入同一 follow 块,
        // 且 follow 中包含 STORE ← PHI(switch 的结果值被存入变量).
        // 此时 case 体应翻译为"result = 值; break;"而非 return——
        // return 会改变控制流语义(源码中 switch 表达式不返回方法).
        BasicBlock follow = detectSwitchFollow(info, graph);
        Variable switchResultVar = null;
        if (follow != null) {
            for (IrInstruction fi : ir.instructionsOf(follow)) {
                if (fi.opcode() == IrOpcode.STORE && fi.operands().size() >= 2
                        && fi.operands().get(0) instanceof Variable v
                        && fi.operands().get(1) instanceof InstructionRef ref
                        && ref.instruction().opcode() == IrOpcode.PHI) {
                    switchResultVar = v;
                    break;
                }
            }
        }

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
        boolean nonVoidMethod = ir.method().returnType() != null
                && ir.method().returnType().kind() != TypeKind.VOID;
        // 保存分支上下文,为每个 case 体设置 PHI 解析上下文
        Set<Integer> prevBranchBlocks = ops.currentBranchBlocks();
        try {
            for (var entry : info.caseBodies().entrySet()) {
                List<Expression> labels = List.of(
                        new LitExpr(entry.getKey(), JavaType.INT));
                // 设置 case 体块作为 PHI 解析上下文:
                // PHI 节点(如共享 return 块的值合并)将选择本分支的操作数
                Set<Integer> caseBlockIds = new HashSet<>();
                for (BasicBlock b : entry.getValue()) {
                    caseBlockIds.add(b.id());
                }
                ops.setCurrentBranchBlocks(caseBlockIds);
                List<Statement> body = new ArrayList<>();
                if (switchResultVar != null) {
                    // switch 表达式:通过分支上下文解析 follow 中的 PHI,
                    // 生成 result = 值; break;
                    Expression val = ops.resolvePhiAt(follow, ir);
                    if (val != null) {
                        body.add(new ExpressionStatement(new AssignExpr(
                                new VarExpr(switchResultVar.name()), val)));
                        body.add(new com.bingbaihanji.bdec.ast.stmt.BreakStatement());
                    }
                }
                if (body.isEmpty() && isTypeSwitch) {
                    body = translateTypeSwitchCase(ops, entry.getValue(), restartBlock,
                            switchKeySlot, nonVoidMethod, ir, graph);
                }
                if (body.isEmpty()) {
                    // 结构化翻译 case 区域:保留条件块与区域内分支的 if 结构.
                    // 两级字符串 switch 的 case 体 = equals 条件 + 映射赋值,
                    // 逐块平铺翻译会丢失 if 结构,使 StringSwitchRewriter 无法匹配.
                    body = translateSwitchCaseRegion(ops, entry.getValue(), ir, graph,
                            new HashSet<>());
                }
                // case 体为空但块中有 CONST 指令(如 ldc "one"):
                // 常量是 case 的结果值,包装为 return.
                if (body.isEmpty() && nonVoidMethod) {
                    for (BasicBlock b : entry.getValue()) {
                        for (IrInstruction ci : ir.instructionsOf(b)) {
                            if (ci.opcode() == IrOpcode.CONST && !ci.operands().isEmpty()) {
                                Expression constExpr = ops.valueToExpr(ci.operands().getFirst());
                                if (constExpr instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                                        && lit.value() instanceof String) {
                                    body.add(new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(lit));
                                }
                            }
                        }
                    }
                }
                if (nonVoidMethod) {
                    body = AstCleanup.wrapOrphansAsReturns(body);
                }
                // 字节码中 case 体以 goto 跳出 switch 时,补 break 语句
                // 以保持 Java 的"不贯穿"语义(否则 case 会错误地落入下一个 case).
                // typeSwitch 的守卫重启 goto(跳回 INDY 块)是贯穿语义,不加 break.
                if (!isTypeSwitch
                        && AstCleanup.caseEndsWithBreak(entry.getValue(), allCaseBlocks, graph, restartBlock)
                        && !AstCleanup.endsWithTerminator(body)) {
                    body.add(new com.bingbaihanji.bdec.ast.stmt.BreakStatement());
                }
                caseGroups.add(new SwitchStatement.CaseGroup(labels, body, false));
            }
            if (!info.defaultBody().isEmpty()) {
                Set<Integer> defBlockIds = new HashSet<>();
                for (BasicBlock b : info.defaultBody()) {
                    defBlockIds.add(b.id());
                }
                ops.setCurrentBranchBlocks(defBlockIds);
                List<Statement> defBody = new ArrayList<>();
                if (switchResultVar != null) {
                    // switch 表达式 default 体含守卫条件时
                    //(如 default -> { if (x > 10) yield "big"; else yield "other"; }),
                    // 翻译为 if (cond) result = trueVal; else result = falseVal;
                    Statement guardBody = translateSwitchExprGuard(ops,
                            info.defaultBody(), follow, switchResultVar, ir, graph);
                    if (guardBody != null) {
                        defBody.add(guardBody);
                    } else {
                        Expression val = ops.resolvePhiAt(follow, ir);
                        if (val != null) {
                            defBody.add(new ExpressionStatement(new AssignExpr(
                                    new VarExpr(switchResultVar.name()), val)));
                            defBody.add(new com.bingbaihanji.bdec.ast.stmt.BreakStatement());
                        }
                    }
                }
                if (defBody.isEmpty()) {
                    for (BasicBlock b : info.defaultBody()) {
                        defBody.addAll(ops.translateBlockGroup(new BlockGroup(b), ir));
                    }
                }
                if (isTypeSwitch && nonVoidMethod
                        && defBody.stream().noneMatch(x -> x instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement)) {
                    for (BasicBlock b : info.defaultBody()) {
                        for (IrInstruction ci : ir.instructionsOf(b)) {
                            if (ci.opcode() == IrOpcode.CONST && !ci.operands().isEmpty()) {
                                Expression constExpr = ops.valueToExpr(ci.operands().getFirst());
                                if (constExpr instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                                        && lit.value() instanceof String) {
                                    defBody.add(new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(lit));
                                    break;
                                }
                            }
                        }
                    }
                }
                if (nonVoidMethod) {
                    defBody = AstCleanup.wrapOrphansAsReturns(defBody);
                }
                if (!isTypeSwitch
                        && AstCleanup.caseEndsWithBreak(info.defaultBody(), allCaseBlocks, graph, restartBlock)
                        && !AstCleanup.endsWithTerminator(defBody)) {
                    defBody.add(new com.bingbaihanji.bdec.ast.stmt.BreakStatement());
                }
                caseGroups.add(new SwitchStatement.CaseGroup(List.of(), defBody, true));
            }
        } finally {
            ops.setCurrentBranchBlocks(prevBranchBlocks);
        }

        SwitchStatement sw = new SwitchStatement(discriminant, caseGroups);
        List<Statement> wrapper = new ArrayList<>(preSwitch);
        if (isTypeSwitch) {
            // typeSwitch 判别式是 INDY 结果,Java 中不可表达.
            // 声明合成 int 变量保持代码可编译.
            wrapper.add(new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                    JavaType.INT, "switchKey",
                    new LitExpr(0, JavaType.INT)));
            wrapper.add(sw);
            return new BlockStatement(wrapper);
        }
        if (switchResultVar != null) {
            // switch 表达式:声明结果变量,case 体中通过赋值产生结果
            wrapper.add(new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                    switchResultVar.type(), switchResultVar.name(), null));
            wrapper.add(sw);
            return new BlockStatement(wrapper);
        }
        wrapper.add(sw);
        return wrapper.size() == 1 ? sw : new BlockStatement(wrapper);
    }


    /**
     * 结构化翻译 switch case 区域:保留区域内条件块的 if 结构.
     *
     * <p>两级字符串 switch 的 case 体是"equals 条件 + 映射赋值"
     * (如 case 97299: if (var2.equals("bar")) var3 = 1; break;).
     * 逐块平铺翻译会丢失 if 结构,使 StringSwitchRewriter 无法匹配.
     * 本方法从区域入口(起始偏移最小的块——javac 将 case 目标
     * 布局在其分支体之前)递归翻译,条件块按 CFG 边构造 if:</p>
     * <ul>
     *   <li>TRUE_BRANCH 后继在区域内 → 该后继是"跳转满足"分支</li>
     *   <li>FALSE_BRANCH 后继在区域内 → 该后继是"直落"分支(条件取反)</li>
     *   <li>两分支都在区域内 → 完整 if-else</li>
     * </ul>
     */
    static List<Statement> translateSwitchCaseRegion(ReducerOps ops, Set<BasicBlock> region, LinearIr ir,
                                                      ControlFlowGraph graph,
                                                      Set<BasicBlock> visited) {
        BasicBlock entry = null;
        for (BasicBlock b : region) {
            if (entry == null || b.startOffset() < entry.startOffset()) {
                entry = b;
            }
        }
        if (entry == null) {
            return List.of();
        }
        return translateSwitchCaseBlock(ops, entry, region, ir, graph, visited);
    }


    /** 递归翻译 case 区域内的单个块. */
    static List<Statement> translateSwitchCaseBlock(ReducerOps ops, BasicBlock b, Set<BasicBlock> region,
                                                     LinearIr ir, ControlFlowGraph graph,
                                                     Set<BasicBlock> visited) {
        if (!visited.add(b)) {
            return List.of();
        }
        boolean hasCond = ir.instructionsOf(b).stream()
                .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
        if (!hasCond) {
            return ops.translateBlockGroup(new BlockGroup(b), ir);
        }
        // 条件块:条件前的语句 + 基于 CFG 边的 if 结构
        List<Statement> pre = ops.translateHeaderNonCondition(new BlockGroup(b), ir);
        Expression cond = AstCleanup.simplifyCondition(ops.extractConditionFromHeader(b, ir));
        BasicBlock trueSucc = null;
        BasicBlock falseSucc = null;
        for (var e : graph.outgoingOf(b)) {
            if (e.kind() == EdgeKind.EXCEPTION) {
                continue;
            }
            if (region.contains(e.target())) {
                if (e.kind() == EdgeKind.TRUE_BRANCH) {
                    trueSucc = e.target();
                } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                    falseSucc = e.target();
                }
            }
        }
        List<Statement> result = new ArrayList<>(pre);
        if (cond == null) {
            return result;
        }
        if (trueSucc != null && falseSucc != null) {
            // 两分支都在区域内:if (cond) { TRUE 分支 } else { FALSE 分支 }
            Statement thenB = StatementUtils.blockOf(
                    translateSwitchCaseBlock(ops, trueSucc, region, ir, graph, visited));
            Statement elseB = StatementUtils.blockOf(
                    translateSwitchCaseBlock(ops, falseSucc, region, ir, graph, visited));
            result.add(new IfStatement(cond, thenB, elseB));
        } else if (trueSucc != null) {
            // 仅"跳转满足"分支在区域内
            result.add(new IfStatement(cond,
                    StatementUtils.blockOf(
                            translateSwitchCaseBlock(ops, trueSucc, region, ir, graph, visited)),
                    null));
        } else if (falseSucc != null) {
            // 仅"直落"分支在区域内:if (!cond) { ... }
            result.add(new IfStatement(AstCleanup.negateCond(cond),
                    StatementUtils.blockOf(
                            translateSwitchCaseBlock(ops, falseSucc, region, ir, graph, visited)),
                    null));
        }
        return result;
    }


    /** 递归翻译 case 区域内的单个块. */
    static BasicBlock detectSwitchFollow(SwitchInfo info, ControlFlowGraph graph) {
        Set<BasicBlock> allBody = new HashSet<>();
        info.caseBodies().values().forEach(allBody::addAll);
        allBody.addAll(info.defaultBody());
        BasicBlock common = null;
        for (BasicBlock b : allBody) {
            for (var e : graph.outgoingOf(b)) {
                BasicBlock t = e.target();
                if (t == graph.exitBlock() || allBody.contains(t)) {
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


    /**
     * 翻译 switch 表达式的守卫 default 体:含 CONDITION 块的 default
     * 对应 yield 分支结构,按每个分支的块上下文解析 PHI 值.
     * 无守卫时返回 null(回退到常规的单值解析).
     */
    static Statement translateSwitchExprGuard(ReducerOps ops, Set<BasicBlock> blocks,
                                               BasicBlock follow,
                                               Variable switchResultVar,
                                               LinearIr ir,
                                               ControlFlowGraph graph) {
        for (BasicBlock b : blocks) {
            boolean hasCond = ir.instructionsOf(b).stream()
                    .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
            if (!hasCond) {
                continue;
            }
            BasicBlock trueTarget = null;
            BasicBlock falseTarget = null;
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() == EdgeKind.TRUE_BRANCH) {
                    trueTarget = e.target();
                } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                    falseTarget = e.target();
                }
            }
            if (trueTarget == null || falseTarget == null) {
                continue;
            }
            Expression cond = AstCleanup.simplifyCondition(ops.extractConditionFromHeader(b, ir));
            List<Statement> pre = ops.translateHeaderNonCondition(new BlockGroup(b), ir);
            // 每个分支的 PHI 值(按分支块上下文解析)
            Expression trueVal = resolvePhiForBlocks(ops, follow, Set.of(trueTarget.id()), ir);
            Expression falseVal = resolvePhiForBlocks(ops, follow, Set.of(falseTarget.id()), ir);
            if (trueVal == null || falseVal == null) {
                return null;
            }
            String varName = switchResultVar.name();
            List<Statement> result = new ArrayList<>(pre);
            List<Statement> thenStmts = new ArrayList<>(List.of(
                    new ExpressionStatement(new AssignExpr(
                            new VarExpr(varName), trueVal))));
            thenStmts.add(new com.bingbaihanji.bdec.ast.stmt.BreakStatement());
            List<Statement> elseStmts = new ArrayList<>(List.of(
                    new ExpressionStatement(new AssignExpr(
                            new VarExpr(varName), falseVal))));
            elseStmts.add(new com.bingbaihanji.bdec.ast.stmt.BreakStatement());
            result.add(new IfStatement(cond,
                    new BlockStatement(thenStmts), new BlockStatement(elseStmts)));
            return StatementUtils.blockOf(result);
        }
        return null;
    }


    /** 按指定块上下文解析 follow 中的 PHI 值 */
    static Expression resolvePhiForBlocks(ReducerOps ops, BasicBlock follow, Set<Integer> blockIds,
                                           LinearIr ir) {
        Set<Integer> prev = ops.currentBranchBlocks();
        try {
            ops.setCurrentBranchBlocks(blockIds);
            return ops.resolvePhiAt(follow, ir);
        } finally {
            ops.setCurrentBranchBlocks(prev);
        }
    }


    /**
     * 翻译 typeSwitch 的 case 体:守卫条件块构建嵌套 if,
     * 守卫失败的重启块(istore 索引 + goto 重启块)视为贯穿(无输出).
     * 仅含常量结果的分支块包装为 return.
     */
    static List<Statement> translateTypeSwitchCase(ReducerOps ops, Set<BasicBlock> blocks,
                                                    BasicBlock restartBlock,
                                                    int switchKeySlot,
                                                    boolean nonVoid,
                                                    LinearIr ir,
                                                    ControlFlowGraph graph) {
        List<Statement> stmts = new ArrayList<>();
        Set<BasicBlock> emitted = new HashSet<>();
        for (BasicBlock b : blocks) {
            if (emitted.contains(b)) {
                continue;
            }
            emitted.add(b);
            // 守卫条件块:构建嵌套 if
            boolean hasCond = ir.instructionsOf(b).stream()
                    .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
            if (hasCond) {
                BasicBlock trueTarget = null;
                BasicBlock falseTarget = null;
                for (var e : graph.outgoingOf(b)) {
                    if (e.kind() == EdgeKind.TRUE_BRANCH) {
                        trueTarget = e.target();
                    } else if (e.kind() == EdgeKind.FALSE_BRANCH) {
                        falseTarget = e.target();
                    }
                }
                if (trueTarget != null && falseTarget != null) {
                    Expression cond = AstCleanup.simplifyCondition(ops.extractConditionFromHeader(b, ir));
                    stmts.addAll(ops.translateHeaderNonCondition(new BlockGroup(b), ir));
                    List<Statement> trueStmts = collectTypeSwitchRegion(ops,
                            trueTarget, blocks, restartBlock, switchKeySlot, nonVoid,
                            emitted, ir, graph);
                    List<Statement> falseStmts = collectTypeSwitchRegion(ops,
                            falseTarget, blocks, restartBlock, switchKeySlot, nonVoid,
                            emitted, ir, graph);
                    if (!trueStmts.isEmpty() || !falseStmts.isEmpty()) {
                        Statement thenBody = trueStmts.isEmpty()
                                ? new BlockStatement(List.of()) : StatementUtils.blockOf(trueStmts);
                        Statement elseBody = falseStmts.isEmpty()
                                ? null : StatementUtils.blockOf(falseStmts);
                        stmts.add(new IfStatement(cond, thenBody, elseBody));
                    }
                    continue;
                }
            }
            // 重启纯块(仅 istore 索引 + goto 重启)→ 跳过
            boolean restartOnly = true;
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() != EdgeKind.EXCEPTION && e.target() != restartBlock) {
                    restartOnly = false;
                    break;
                }
            }
            boolean onlyKeyStores = !ir.instructionsOf(b).isEmpty()
                    && ir.instructionsOf(b).stream().allMatch(i ->
                    i.opcode() == IrOpcode.STORE
                            && !i.operands().isEmpty()
                            && i.operands().getFirst() instanceof Variable v
                            && v.slot() == switchKeySlot);
            if (restartOnly && onlyKeyStores) {
                continue;
            }
            // 普通块
            List<Statement> bs = new ArrayList<>(
                    ops.translateBlockGroup(new BlockGroup(b), ir));
            // 块中以 CONST 结尾(如 ldc "non-positive int")且翻译未产生
            // 该常量时,补 return <const>——typeSwitch 的 case 结果值.
            if (nonVoid) {
                boolean hasConstEnd = false;
                for (IrInstruction ci : ir.instructionsOf(b)) {
                    if (ci.opcode() == IrOpcode.CONST && !ci.operands().isEmpty()) {
                        Expression constExpr = ops.valueToExpr(ci.operands().getFirst());
                        if (constExpr instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                                && lit.value() instanceof String
                                && bs.stream().noneMatch(x -> x instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement)) {
                            bs.add(new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(lit));
                            break;
                        }
                    }
                }
            }
            stmts.addAll(bs);
        }
        return stmts;
    }


    /** 收集 typeSwitch case 体内的分支区域(从 start 出发,限于 case 体内) */
    static List<Statement> collectTypeSwitchRegion(ReducerOps ops, BasicBlock start,
                                                    Set<BasicBlock> caseBlocks,
                                                    BasicBlock restartBlock,
                                                    int switchKeySlot,
                                                    boolean nonVoid,
                                                    Set<BasicBlock> emitted,
                                                    LinearIr ir,
                                                    ControlFlowGraph graph) {
        List<Statement> stmts = new ArrayList<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        Set<BasicBlock> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock b = queue.poll();
            if (!visited.add(b)) {
                continue;
            }
            if (!caseBlocks.contains(b) || emitted.contains(b)) {
                continue;
            }
            emitted.add(b);
            // 重启纯块 → 跳过(贯穿)
            boolean restartOnly = true;
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() != EdgeKind.EXCEPTION && e.target() != restartBlock) {
                    restartOnly = false;
                    break;
                }
            }
            if (restartOnly) {
                continue;
            }
            List<Statement> bs = new ArrayList<>(
                    ops.translateBlockGroup(new BlockGroup(b), ir));
            // 仅含常量结果的分支块 → 包装为 return
            if (bs.isEmpty() && nonVoid) {
                for (IrInstruction ci : ir.instructionsOf(b)) {
                    if (ci.opcode() == IrOpcode.CONST && !ci.operands().isEmpty()) {
                        Expression constExpr = ops.valueToExpr(ci.operands().getFirst());
                        if (constExpr instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                                && lit.value() instanceof String) {
                            bs.add(new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(lit));
                        }
                    }
                }
            }
            stmts.addAll(bs);
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() != EdgeKind.EXCEPTION && caseBlocks.contains(e.target())) {
                    queue.add(e.target());
                }
            }
        }
        return stmts;
    }


}
