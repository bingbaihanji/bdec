package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.semantic.SemanticAnnotation;
import com.bingbaihanji.bdec.semantic.SemanticTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * synchronized 块翻译器——从 {@link TryTranslator} 中提取的 synchronized
 * 专属逻辑(里程碑 Phase 3).
 *
 * <p>包含 synchronized 处理器识别({@link #isSyncHandlerGroup},
 * {@link #groupHasSynchronizedAnnotation},{@link #isSynchronizedHandler}),
 * 监视器对象提取({@link #extractMonitorObject}),同步体收集
 * ({@link #collectSyncBody})与包装({@link #wrapSynchronized}).保持无状态.</p>
 */
public final class SynchronizedTranslator {

    private SynchronizedTranslator() {}

    /** 检查块组是否为 synchronized 处理器(monitorexit + throw). */
    static boolean isSyncHandlerGroup(BlockGroup group, LinearIr ir) {
        boolean hasMonitorExit = false;
        boolean hasThrow = false;
        for (IrInstruction insn : group.allIrInstructions(ir)) {
            if (insn.opcode() == IrOpcode.MONITOR_EXIT) {
                hasMonitorExit = true;
            }
            if (insn.opcode() == IrOpcode.THROW) {
                hasThrow = true;
            }
        }
        return hasMonitorExit && hasThrow;
    }

    /** 检查组内任意 IR 指令是否具有 SYNCHRONIZED_BLOCK 标记.
     *  该方法比匹配输出占位符文本更可靠. */
    static boolean groupHasSynchronizedAnnotation(BlockGroup group, LinearIr ir) {
        return group.allIrInstructions(ir).stream().anyMatch(
                i -> i.hasTag(SemanticTag.SYNCHRONIZED_BLOCK)
                        && i.opcode() == IrOpcode.MONITOR_ENTER);
    }

    /** 检查 TryCatchInfo 是否表示 synchronized 块:
     *  方法包含 MONITOR_ENTER,处理器包含 MONITOR_EXIT + THROW.
     *  MONITOR_ENTER 通常位于 try 范围之前的字节码偏移处,
     *  因此需要搜索整个方法 IR 而非仅限 try 块. */
    static boolean isSynchronizedHandler(TryCatchInfo info, LinearIr ir) {
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
        List<IrInstruction> handlerInsns = TryTranslator.collectHandlerInstructions(info, ir);
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

    /** 从 synchronized try-catch 中提取监视器对象名称 */
    static String extractMonitorObject(TryCatchInfo info, LinearIr ir) {
        ControlFlowGraph cfg = ir.controlFlowGraph();
        Set<BasicBlock> candidates = new java.util.LinkedHashSet<>(info.tryBlocks());
        // monitorenter 通常位于 try 保护区之前(如 offset 4 < startPc 5),
        // 不在 tryBlocks 内——把"后继在 try 区域"的前导块也纳入候选.
        for (BasicBlock b : cfg.blocks()) {
            for (var e : cfg.outgoingOf(b)) {
                if (info.tryBlocks().contains(e.target())) {
                    candidates.add(b);
                }
            }
        }
        for (BasicBlock b : candidates) {
            for (IrInstruction insn : ir.instructionsOf(b)) {
                if (insn.opcode() == IrOpcode.MONITOR_ENTER && !insn.operands().isEmpty()) {
                    Value obj = insn.operands().getFirst();
                    // 类字面量监视器:synchronized (SomeClass.class) 的 MONITOR_ENTER
                    // 操作数是携带类名 String 值的 CONST,渲染为 SomeClass.class
                    //(此前只追 Variable,类字面量回退 "this",静态方法中非法).
                    if (obj instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.CONST
                            && !ref.instruction().operands().isEmpty()
                            && ref.instruction().operands()
                            .getFirst() instanceof com.bingbaihanji.bdec.ir.ConstantValue cv
                            && cv.value() instanceof String className) {
                        int slash = className.lastIndexOf('/');
                        return slash >= 0 ? className.substring(slash + 1) + ".class"
                                : className + ".class";
                    }
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

    /** 收集 synchronized 体:从 monitorenter 组的后继组开始,
     *  沿单非异常后继链直到含 MONITOR_EXIT 的组.
     *  被吸收的组标记为已消费. */
    static Statement collectSyncBody(ReducerOps ops, BlockGroup group, List<BlockGroup> allGroups,
                                     Set<BlockGroup> consumed, LinearIr ir,
                                     ControlFlowGraph graph) {
        List<Statement> bodyStmts = new ArrayList<>();
        BasicBlock cur = group.last();
        Set<BasicBlock> visited = new HashSet<>();
        while (cur != null && visited.add(cur)) {
            BasicBlock next = null;
            for (var e : graph.outgoingOf(cur)) {
                if (e.kind() != EdgeKind.EXCEPTION) {
                    if (next == null) {
                        next = e.target();
                    } else {
                        next = null; // 多个后继 → 停止
                        break;
                    }
                }
            }
            if (next == null || next == graph.exitBlock()) {
                break;
            }
            BlockGroup ng = null;
            for (BlockGroup g2 : allGroups) {
                if (!consumed.contains(g2) && g2.blocks().contains(next)) {
                    ng = g2;
                    break;
                }
            }
            if (ng == null) {
                break;
            }
            consumed.add(ng);
            boolean hasExit = ng.allIrInstructions(ir).stream()
                    .anyMatch(i -> i.opcode() == IrOpcode.MONITOR_EXIT);
            Statement bs = ops.translateGroup(ng, ir);
            if (bs != null) {
                bodyStmts.add(bs);
            }
            if (hasExit) {
                break;
            }
            cur = ng.last();
        }
        if (bodyStmts.isEmpty()) {
            return null;
        }
        if (bodyStmts.size() == 1) {
            return bodyStmts.getFirst();
        }
        return new BlockStatement(bodyStmts);
    }

    /** 将语句树包装为 synchronized 块 */
    static SynchronizedStatement wrapSynchronized(Statement body,
                                                  BlockGroup group, LinearIr ir) {
        // 从 MONITOR_ENTER 注解中找到监视器对象
        String monitorObj = "obj";
        for (IrInstruction insn : group.allIrInstructions(ir)) {
            if (insn.opcode() == IrOpcode.MONITOR_ENTER
                    && insn.hasTag(SemanticTag.SYNCHRONIZED_BLOCK)) {
                var ann = insn.getAnnotation(SemanticTag.SYNCHRONIZED_BLOCK);
                if (ann != null) {
                    String desc = ann.getString(SemanticAnnotation.KEY_MONITOR_OBJECT);
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
                    if (es.expression() instanceof VarExpr v
                            && ("/* monitor enter */".equals(v.name())
                            || "/* monitor exit */".equals(v.name()))) {
                        continue;
                    }
                }
                filtered.add(s);
            }
            body = new BlockStatement(filtered);
        }

        // stripSyncPreamble 只处理 BlockStatement 体——单语句归一化
        if (!(body instanceof BlockStatement)) {
            body = new BlockStatement(List.of(body));
        }
        SynchronizedStatement syncStmt = new SynchronizedStatement(
                new VarExpr(monitorObj), body);
        // 剥离 monitorexit 异常处理器的伪影
        //(Throwable varN; throw varN; 等)
        return AstCleanup.stripSyncPreamble(syncStmt);
    }
}
