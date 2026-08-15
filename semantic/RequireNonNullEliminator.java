package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.bytecode.model.ExceptionHandlerModel;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code Objects.requireNonNull()} 和 {@code Object.getClass()} 空检查调用的消除器.
 *
 * <p>消除由编译器生成的,仅用于空值检查的 {@code Objects.requireNonNull()} 和
 * {@code Object.getClass()} 调用伪影.
 *
 * <p>字节码层面的模式为:
 * <pre>
 *   DUP
 *   INVOKEVIRTUAL requireNonNull/getClass
 *   POP
 * </pre>
 *
 * <p>在 IR 层面表现为:
 * <pre>
 *   LOAD obj        → 压入 obj
 *   INVOKE requireNonNull/getClass(obj) → 返回 obj
 *   (结果未被任何指令消费,或仅被 POP 消费)
 * </pre>
 *
 * <p>设计参考了 CFR 的 {@code Op02GetClassRewriter},该 pass 在指令级别
 *(任何分析之前)检测上述字节码模式.
 */
public final class RequireNonNullEliminator {

    /** {@code Objects.requireNonNull} 所在的完全限定类名 */
    private static final String REQUIRE_NON_NULL_CLASS = "java/util/Objects";

    /** 目标方法名:requireNonNull */
    private static final String REQUIRE_NON_NULL_METHOD = "requireNonNull";

    /** 目标方法名:getClass */
    private static final String GET_CLASS_METHOD = "getClass";

    /**
     * 消除 requireNonNull / getClass 空检查调用.
     *
     * @param ir 待处理的线性 IR
     * @return 如果移除了任何指令则返回 true
     */
    public boolean eliminate(LinearIr ir) {
        boolean changed = false;
        List<IrInstruction> instructions = new ArrayList<>(ir.instructions());

        // 第一遍:找出所有调用了 requireNonNull/getClass 且结果未被消费的 INVOKE 指令
        Set<Integer> consumedIds = buildConsumedIds(instructions);
        Set<Integer> toRemove = new HashSet<>();

        for (IrInstruction insn : instructions) {
            if (insn.opcode() != IrOpcode.INVOKE) {
                continue;
            }

            String nameHint = insn.nameHint();
            if (nameHint == null) {
                continue;
            }

            // 检查是否为 requireNonNull 或 getClass 方法调用
            boolean isRequireNonNull = REQUIRE_NON_NULL_METHOD.equals(nameHint);
            boolean isGetClass = GET_CLASS_METHOD.equals(nameHint);
            if (!isRequireNonNull && !isGetClass) {
                continue;
            }

            // 对于 requireNonNull:验证声明类确实是 java/util/Objects
            if (isRequireNonNull) {
                var dcAnn = insn.getAnnotation(SemanticTag.DECLARING_CLASS);
                String declaringClass = dcAnn != null
                        ? dcAnn.getString(SemanticAnnotation.KEY_DECLARING_CLASS)
                        : null;
                if (!REQUIRE_NON_NULL_CLASS.equals(declaringClass)) {
                    // 不是 Objects.requireNonNull,可能是其他类的同名方法,跳过
                    continue;
                }
            }

            // 检查结果是否未被消费(真正的空检查模式特征)
            if (consumedIds.contains(insn.id())) {
                // 结果被使用,不能消除——这是真正的业务调用
                continue;
            }

            // 位于 try 区域内的空检查不消除:其 NPE 可能是控制流触发.
            // 混淆器用 try { obj.getClass(); } catch (NPE) { 真实路径 } 转移
            // 控制流——消除后 NPE 不再触发,catch 分支永不执行,语义错误.
            if (isInTryRegion(insn.sourceOffset(), ir.method().exceptionHandlers())) {
                continue;
            }

            // 确保 INVOKE 至少有一个操作数(接收者对象 + 参数)
            if (insn.operands().isEmpty()) {
                continue;
            }

            // 标记为待移除,并标注第一个操作数(接收者)应当透传,替代此调用
            toRemove.add(insn.id());
            insn.addAnnotation(SemanticAnnotation.of(
                    SemanticTag.NULL_CHECK_REMOVED,
                    SemanticAnnotation.KEY_ORIGINAL_METHOD, nameHint));
            changed = true;
        }

        // 第二遍:移除已标记的指令 + 连带移除因此孤立的中间指令.
        // 空检查被移除后,其接收者 LOAD 等中间指令可能失去全部消费者,
        // 残留为裸语句(如 "s;")导致无法重编译——迭代移除无消费的中间指令.
        if (changed) {
            boolean more;
            do {
                more = false;
                Set<Integer> aliveConsumed = buildConsumedIds(
                        instructions, toRemove);
                for (IrInstruction insn : instructions) {
                    if (toRemove.contains(insn.id())
                            || !isSideEffectFree(insn.opcode())) {
                        continue;
                    }
                    if (!aliveConsumed.contains(insn.id())) {
                        toRemove.add(insn.id());
                        more = true;
                    }
                }
            } while (more);

            List<IrInstruction> filtered = new ArrayList<>();
            for (IrInstruction insn : instructions) {
                if (!toRemove.contains(insn.id())) {
                    // 重写操作数:若操作数引用了已被移除的空检查调用,
                    // 则将该引用替换为调用的接收者对象
                    filtered.add(rewriteOperands(insn, toRemove, instructions));
                }
            }
            // 替换 LinearIr 中的指令列表
            ir.replaceInstructions(filtered);
        }

        return changed;
    }

    /** 指令是否无副作用(纯中间值,可安全移除当无人消费). */
    private static boolean isSideEffectFree(IrOpcode op) {
        return switch (op) {
            case LOAD, CONST, CAST, BINARY, COMPARE, UNARY,
                    ARRAY_LENGTH, INSTANCE_OF -> true;
            default -> false;
        };
    }

    /** 构建被"未被移除指令"消费的指令 ID 集合. */
    private static Set<Integer> buildConsumedIds(
            List<IrInstruction> instructions, Set<Integer> excluded) {
        Set<Integer> consumed = new HashSet<>();
        for (IrInstruction insn : instructions) {
            if (excluded.contains(insn.id())) {
                continue;
            }
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                }
            }
        }
        return consumed;
    }

    /** 指令偏移是否位于某异常处理器的受保护范围内(try 区域). */
    private static boolean isInTryRegion(int offset, List<ExceptionHandlerModel> handlers) {
        if (handlers == null) {
            return false;
        }
        for (ExceptionHandlerModel h : handlers) {
            if (offset >= h.startPc() && offset < h.endPc()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建被其他指令消费的指令 ID 集合.
     *
     * @param instructions 当前所有 IR 指令列表
     * @return 被至少一条其他指令引用的指令 ID 集合
     */
    private Set<Integer> buildConsumedIds(List<IrInstruction> instructions) {
        Set<Integer> consumed = new HashSet<>();
        for (IrInstruction insn : instructions) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                }
            }
        }
        return consumed;
    }

    /**
     * 重写指令的操作数:如果操作数引用了已移除的空检查调用,
     * 则将其替换为该调用的接收者对象.
     *
     * @param insn           当前指令
     * @param removed        已移除的指令 ID 集合
     * @param allInstructions 所有原始指令列表
     * @return 重写后的指令(若无需重写则返回原指令)
     */
    private IrInstruction rewriteOperands(IrInstruction insn, Set<Integer> removed,
                                          List<IrInstruction> allInstructions) {
        boolean needsRewrite = false;
        for (Value op : insn.operands()) {
            if (op instanceof InstructionRef ref && removed.contains(ref.instruction().id())) {
                needsRewrite = true;
                break;
            }
        }
        if (!needsRewrite) {
            return insn;
        }

        // 替换操作数:对每个已移除的空检查引用,用其接收者对象替代
        List<Value> newOperands = new ArrayList<>();
        for (Value op : insn.operands()) {
            if (op instanceof InstructionRef ref && removed.contains(ref.instruction().id())) {
                IrInstruction removedInsn = ref.instruction();
                // 接收者是空检查 INVOKE 指令的第一个操作数
                if (!removedInsn.operands().isEmpty()) {
                    newOperands.add(removedInsn.operands().getFirst());
                }
            } else {
                newOperands.add(op);
            }
        }

        // 创建操作数已重写的新指令
        return new IrInstruction(insn.id(), insn.opcode(), insn.resultType(),
                newOperands, insn.sourceOffset(), insn.blockId(),
                insn.originalOpcode(), insn.nameHint());
    }
}
