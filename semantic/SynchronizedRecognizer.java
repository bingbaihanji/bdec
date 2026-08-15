package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.ExceptionRange;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * synchronized 块识别器.
 *
 * <p>从 {@code monitorenter/monitorexit} IR 指令及其关联的异常处理器中
 * 识别出 Java 语言的 synchronized 块结构.
 *
 * <p>JVM 中 {@code synchronized(obj) { ... }} 的字节码模式为:
 * <pre>
 *   load obj        → 加载监视器对象
 *   dup             (可选——为 monitorexit 保存一份副本)
 *   store tmp       → 存储到临时变量
 *   monitorenter    (弹出 obj)
 *   try {
 *     ... 方法体 ...
 *     load tmp
 *     monitorexit   → 正常退出路径
 *   } catch (Throwable t) {
 *     load tmp
 *     monitorexit   → 异常退出路径
 *     athrow
 *   }
 * </pre>
 *
 * <p>设计参考了 Vineflower 的 {@code DomHelper.buildSynchronized()}(在结构化后检测模式)
 * 和 CFR 的 {@code SynchronizedBlocks}(从 monitorenter 进行 DFS).
 */
public final class SynchronizedRecognizer {

    /**
     * 识别 IR 中的 synchronized 块并添加语义注解标记.
     *
     * @param ir  待处理的线性 IR
     * @param cfg 控制流图(用于异常处理器分析)
     * @return 如果识别出任何 synchronized 块则返回 true
     */
    public boolean recognize(LinearIr ir, ControlFlowGraph cfg) {
        boolean changed = false;
        List<IrInstruction> instructions = ir.instructions();

        // 收集所有 MONITOR_ENTER 指令
        List<IrInstruction> monitorEnters = new ArrayList<>();
        for (IrInstruction insn : instructions) {
            if (insn.opcode() == IrOpcode.MONITOR_ENTER) {
                monitorEnters.add(insn);
            }
        }
        if (monitorEnters.isEmpty()) {
            return false;
        }

        // 构建基本块 ID → 指令列表索引
        Map<Integer, List<IrInstruction>> blockInsns = buildBlockIndex(instructions);

        for (IrInstruction enter : monitorEnters) {
            if (enter.operands().isEmpty()) {
                continue;
            }

            Value monitorObj = enter.operands().getFirst();

            // 查找包含该 monitorenter 所在基本块的异常处理器
            BasicBlock enterBlock = findBlock(cfg, enter.blockId());
            if (enterBlock == null) {
                continue;
            }

            ExceptionRange handler = findCoveringHandler(cfg, enterBlock);
            if (handler == null) {
                // monitorenter 位于保护区之前(其直落后继才是 sync 体):
                // javac 将 try 范围起点设在 monitorenter 之后,
                // 需要检查后继块是否被处理器覆盖.
                for (BasicBlock succ : cfg.successorsOf(enterBlock)) {
                    handler = findCoveringHandler(cfg, succ);
                    if (handler != null) {
                        break;
                    }
                }
            }
            if (handler == null) {
                continue;
            }

            // monitorenter 前通常有 dup; astore N(监视器对象副本),
            // 处理器的 monitorexit 使用副本槽位——记录以便匹配.
            int copySlot = -1;
            if (monitorObj instanceof Variable mv) {
                for (IrInstruction insn : blockInsns.getOrDefault(enterBlock.id(), List.of())) {
                    if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                            && insn.operands().get(1) instanceof Variable src
                            && src.slot() == mv.slot()
                            && insn.operands().getFirst() instanceof Variable dst) {
                        copySlot = dst.slot();
                    }
                }
            }

            // 验证异常处理器包含 MONITOR_EXIT + THROW 模式.
            // MONITOR_EXIT 与 THROW 可能分布在同一处理器的相邻块中
            //(handler 链:B59 monitorexit → B60 athrow),沿非异常后继收集.
            BasicBlock handlerBlock = handler.handlerBlock();
            List<IrInstruction> handlerInsns = new ArrayList<>();
            Set<BasicBlock> chainVisited = new HashSet<>();
            Deque<BasicBlock> chainQueue = new ArrayDeque<>();
            chainQueue.add(handlerBlock);
            while (!chainQueue.isEmpty()) {
                BasicBlock cur = chainQueue.poll();
                if (!chainVisited.add(cur)) {
                    continue;
                }
                handlerInsns.addAll(blockInsns.getOrDefault(cur.id(), List.of()));
                List<BasicBlock> nonExSuccs = new ArrayList<>();
                for (var e : cfg.outgoingOf(cur)) {
                    if (e.kind() != com.bingbaihanji.bdec.cfg.EdgeKind.EXCEPTION
                            && e.target() != cfg.exitBlock()) {
                        nonExSuccs.add(e.target());
                    }
                }
                if (nonExSuccs.size() == 1) {
                    chainQueue.add(nonExSuccs.getFirst());
                }
            }
            if (!isMonitorExitThrow(handlerInsns, monitorObj, copySlot)) {
                continue;
            }

            // 在 try 方法体中查找 monitorexit(正常退出路径)
            boolean foundNormalExit = false;
            for (var entry : blockInsns.entrySet()) {
                if (entry.getKey() == handlerBlock.id()) {
                    continue;
                }
                for (IrInstruction insn : entry.getValue()) {
                    if (insn.opcode() == IrOpcode.MONITOR_EXIT && matchesObject(insn, monitorObj, copySlot)) {
                        foundNormalExit = true;
                        break;
                    }
                }
                if (foundNormalExit) {
                    break;
                }
            }

            if (foundNormalExit) {
                // 标记 monitorenter 所在基本块为 synchronized 块
                enter.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.SYNCHRONIZED_BLOCK,
                        SemanticAnnotation.KEY_MONITOR_OBJECT,
                        describeMonitor(monitorObj)));

                // 标记所有 MONITOR_EXIT 指令为待移除(后续由结构化层处理)
                for (var entry : blockInsns.entrySet()) {
                    for (IrInstruction insn : entry.getValue()) {
                        if (insn.opcode() == IrOpcode.MONITOR_EXIT && matchesObject(insn, monitorObj, copySlot)) {
                            insn.addAnnotation(SemanticAnnotation.of(
                                    SemanticTag.SYNCHRONIZED_BLOCK));
                        }
                    }
                }
                changed = true;
            }
        }

        return changed;
    }

    /** 构建基本块 ID → 指令列表的索引映射 */
    private Map<Integer, List<IrInstruction>> buildBlockIndex(List<IrInstruction> instructions) {
        Map<Integer, List<IrInstruction>> index = new HashMap<>();
        for (IrInstruction insn : instructions) {
            index.computeIfAbsent(insn.blockId(), k -> new ArrayList<>()).add(insn);
        }
        return index;
    }

    /** 根据 ID 在控制流图中查找对应的基本块 */
    private BasicBlock findBlock(ControlFlowGraph cfg, int blockId) {
        for (BasicBlock b : cfg.blocks()) {
            if (b.id() == blockId) {
                return b;
            }
        }
        return null;
    }

    /**
     * 查找覆盖指定基本块的异常处理器(优先匹配 catch-all).
     *
     * @param cfg   控制流图
     * @param block 目标基本块
     * @return 覆盖该块的异常处理器范围,未找到则返回 null
     */
    private ExceptionRange findCoveringHandler(ControlFlowGraph cfg, BasicBlock block) {
        for (ExceptionRange er : cfg.exceptionRanges()) {
            if (er.catchType() == null && covers(er, block)) {
                // catchType == null 表示 finally 或 catch-all
                // 检查处理器块是否包含 monitorexit
                return er;
            }
        }
        // 也检查带类型的异常处理器(可能包含监视器模式)
        for (ExceptionRange er : cfg.exceptionRanges()) {
            if (er.catchType() != null && covers(er, block)) {
                return er;
            }
        }
        return null;
    }

    /** 检查异常处理器范围是否覆盖指定基本块 */
    private boolean covers(ExceptionRange er, BasicBlock block) {
        int start = er.startPc();
        int end = er.endPc();
        return block.startOffset() >= start && block.startOffset() < end;
    }

    /**
     * 检查异常处理器指令列表是否包含 MONITOR_EXIT + THROW 模式.
     */
    private boolean isMonitorExitThrow(List<IrInstruction> handlerInsns, Value monitorObj, int copySlot) {
        boolean hasMonitorExit = false;
        boolean hasThrow = false;
        for (IrInstruction insn : handlerInsns) {
            if (insn.opcode() == IrOpcode.MONITOR_EXIT && matchesObject(insn, monitorObj, copySlot)) {
                hasMonitorExit = true;
            }
            if (insn.opcode() == IrOpcode.THROW) {
                hasThrow = true;
            }
        }
        return hasMonitorExit && hasThrow;
    }

    /** 检查 MONITOR_EXIT 指令是否引用了预期的监视器对象 */
    private boolean matchesObject(IrInstruction monInsn, Value expected, int copySlot) {
        if (monInsn.operands().isEmpty()) {
            return false;
        }
        Value obj = monInsn.operands().getFirst();
        if (expected instanceof Variable ev && obj instanceof Variable ov) {
            return ev.slot() == ov.slot() || ov.slot() == copySlot;
        }
        return obj.equals(expected);
    }

    /** 将监视器对象 Value 转换为可读的描述字符串 */
    private String describeMonitor(Value v) {
        if (v instanceof Variable var) {
            return var.slot() == 0 ? "this" : "var" + var.slot();
        }
        // 类字面量监视器:synchronized (SomeClass.class) 的 MONITOR_ENTER
        // 操作数是携带类名 String 值的 CONST,渲染为 SomeClass.class.
        if (v instanceof com.bingbaihanji.bdec.ir.InstructionRef ref
                && ref.instruction().opcode() == IrOpcode.CONST
                && !ref.instruction().operands().isEmpty()
                && ref.instruction().operands().getFirst() instanceof com.bingbaihanji.bdec.ir.ConstantValue cv
                && cv.value() instanceof String className) {
            int slash = className.lastIndexOf('/');
            String simple = slash >= 0 ? className.substring(slash + 1) : className;
            return simple + ".class";
        }
        return v.toString();
    }
}
