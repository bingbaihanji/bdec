package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.opcode.Opcode;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IR构建器——将栈式字节码转换为寄存器式IR的栈模拟引擎.
 * <p>
 * 每条JVM指令通过模拟其在操作数栈和局部变量表上的效果来处理.
 * 主方法{@link #simulateBlock}的switch语句将各指令分发到对应类别的处理函数.
 * 元数据(原始字节码操作码,字段名/方法名)保留在{@link IrInstruction}中,
 * 以便下游pass能生成正确的运算符和名称.
 * </p>
 *
 * <h3>处理架构</h3>
 * <ul>
 *   <li>{@link #handleConstant} — 常量加载(iconst_0,ldc等)</li>
 *   <li>{@link #handleLoad} / {@link #handleStore} — 局部变量读写</li>
 *   <li>{@link #handleArithmetic} / {@link #handleNegate} — 算术运算</li>
 *   <li>{@link #handleComparison} / {@link #handleCondition} — 比较与分支</li>
 *   <li>{@link #handleFieldLoad} / {@link #handleFieldStore} — 字段访问</li>
 *   <li>{@link #handleInvoke} / {@link #handleInvokeDynamic} — 方法调用</li>
 *   <li>{@link #handleNew} / {@link #handleNewArray} — 对象与数组创建</li>
 *   <li>{@link #handleConversion} / {@link #handleCheckCast} — 类型转换</li>
 * </ul>
 */
public final class IrBuilder {

    /** 下一条指令的ID计数器 */
    private int nextInsnId = 0;

    /** 下一个变量的ID计数器 */
    private int nextVarId = 0;

    /** 当前方法的局部变量表名称(槽位 → 名称),在 simulateBlock 时设置. */
    private java.util.Map<Integer, String> currentLvtNames = java.util.Collections.emptyMap();
    private MethodModel currentMethod = null;

    /** 来自类文件的引导方法列表,用于 invokedynamic 的解析. */
    private List<BootstrapMethodEntry> currentBootstrapMethods = java.util.Collections.emptyList();

    /**
     * 判断一个值是否为类别2类型(long或double,在JVM操作数栈中占用两个槽位).
     */
    private static boolean isCategory2(Value v) {
        return v.type() != null && (v.type().kind() == TypeKind.LONG
                || v.type().kind() == TypeKind.DOUBLE);
    }

    /**
     * 沿InstructionRef链追溯底层常量值.
     * 如果值链末端是CONST指令,则提取其ConstantValue操作数.
     */
    private static ConstantValue unwrapConstant(Value v) {
        if (v instanceof ConstantValue cv) {
            return cv;
        }
        if (v instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            if (def.opcode() == IrOpcode.CONST && !def.operands().isEmpty()) {
                Value inner = def.operands().getFirst();
                if (inner instanceof ConstantValue cv) {
                    return cv;
                }
            }
        }
        return null;
    }

    // ── 基本块排序 ──────────────────────────────────────────────────

    // ── 前驱合并 ────────────────────────────────────────────────────

    /**
     * 通过对每个基本块进行符号执行,从控制流图构建线性IR.
     *
     * @param cfg              控制流图
     * @param method           方法模型
     * @param constantPool     常量池
     * @param bootstrapMethods 引导方法列表
     * @return 构建完成的线性IR
     */
    public LinearIr build(ControlFlowGraph cfg, MethodModel method,
                          ConstantPoolEntry[] constantPool,
                          List<BootstrapMethodEntry> bootstrapMethods) {
        this.currentBootstrapMethods = bootstrapMethods != null
                ? bootstrapMethods : java.util.Collections.emptyList();
        List<IrInstruction> allInstructions = new ArrayList<>();
        List<Variable> variables = new ArrayList<>();
        Map<BasicBlock, FrameState> blockOutputs = new HashMap<>();

        List<BasicBlock> blocks = orderBlocks(cfg);

        int maxLocals = method.maxLocals();
        if (maxLocals <= 0) {
            maxLocals = method.isStatic() ? 0 : 1;
        }

        // 使用具有正确Java类型的参数变量预填充初始FrameState.
        // 这对于boolean参数至关重要:JVM使用int类型(ILOAD/ISTORE),
        // 但实际Java类型是boolean.没有这些信息,下游pass无法区分
        // "boolean flag == 0"(→ !flag)和 "int x == 0".
        FrameState initialFrame = FrameState.withLocals(maxLocals);
        Value[] initLocals = initialFrame.locals();
        int slot = 0;
        if (!method.isStatic()) {
            // 槽位0 = 'this'
            JavaType thisType = com.bingbaihanji.bdec.type.JavaType.classType(
                    "java/lang/Object");
            Variable thisVar = new Variable(slot, 0, thisType, false, slot);
            thisVar.setName("this");
            variables.add(thisVar);
            initLocals[slot] = thisVar;
            slot++;
        }
        if (method.parameterTypes() != null) {
            for (JavaType pt : method.parameterTypes()) {
                if (slot < maxLocals) {
                    Variable pv = new Variable(slot, 0, pt, true, slot);
                    // 如果有局部变量表名称则使用
                    String lvtName = method.localVarNames().get(slot);
                    if (lvtName != null) {
                        pv.setName(lvtName);
                    }
                    variables.add(pv);
                    initLocals[slot] = pv;
                    slot++;
                    // long和double在JVM中占用两个槽位
                    if (pt.kind() == com.bingbaihanji.bdec.type.TypeKind.LONG
                            || pt.kind() == com.bingbaihanji.bdec.type.TypeKind.DOUBLE) {
                        slot++;
                    }
                }
            }
        }

        for (BasicBlock block : blocks) {
            FrameState entry = mergePredecessorStates(block, blockOutputs, cfg, allInstructions, variables);
            if (entry == null) {
                entry = initialFrame.copy();
            }
            FrameState exit = simulateBlock(block, entry, allInstructions, variables, cfg, method, constantPool,
                    method.localVarNames());
            blockOutputs.put(block, exit);
        }

        return new LinearIr(method, cfg, allInstructions, variables);
    }

    // ── 主模拟 — 模拟一个基本块 ───────────────────────────────────────

    /**
     * 返回基本块的排序列表,使每个块的前驱尽可能在其之前处理.
     * 使用工作列表算法:仅当某块的所有前驱均已处理时才发射该块,
     * 对于不可归约循环(irreducible loops)回退到DFS遍历.
     * 这种方式确保了在汇合点能够正确创建PHI节点.
     */
    private List<BasicBlock> orderBlocks(ControlFlowGraph cfg) {
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> emitted = new HashSet<>();
        Set<BasicBlock> inQueue = new HashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();

        // 从入口块的后继开始(入口块本身不需要模拟)
        for (BasicBlock succ : cfg.successorsOf(cfg.entryBlock())) {
            if (succ != cfg.exitBlock()) {
                queue.add(succ);
                inQueue.add(succ);
            }
        }

        int maxIter = cfg.blockCount() * 4; // 不可归约循环的安全上限
        while (!queue.isEmpty() && maxIter-- > 0) {
            BasicBlock b = queue.poll();
            inQueue.remove(b);
            if (emitted.contains(b)) {
                continue;
            }

            // 可发射该块的条件:所有前驱均已发射(或者是入口/出口块),
            // 或等待次数过多时使用回退策略
            boolean allPredsReady = true;
            for (BasicBlock pred : cfg.predecessorsOf(b)) {
                if (pred != cfg.entryBlock() && pred != cfg.exitBlock()
                        && !emitted.contains(pred)) {
                    allPredsReady = false;
                    break;
                }
            }

            if (allPredsReady) {
                emitted.add(b);
                result.add(b);
                // 将后继加入队列
                for (BasicBlock succ : cfg.successorsOf(b)) {
                    if (succ != cfg.exitBlock() && !emitted.contains(succ)
                            && !inQueue.contains(succ)) {
                        queue.add(succ);
                        inQueue.add(succ);
                    }
                }
            } else {
                // 重新放回队尾——等前驱处理完后重试
                queue.add(b);
                inQueue.add(b);
            }
        }

        // 回退:任何仍未处理的基本块(不可归约循环中的块)——使用DFS
        if (emitted.size() < cfg.blockCount() - 2) { // 减去入口+出口
            for (BasicBlock b : cfg.blocks()) {
                if (b != cfg.entryBlock() && b != cfg.exitBlock()
                        && !emitted.contains(b)) {
                    result.add(b);
                    emitted.add(b);
                }
            }
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  按操作码类别的处理函数
    // ═══════════════════════════════════════════════════════════════════

    // ── 栈操作 ─────────────────────────────────────────────────────

    /**
     * 合并一个基本块所有前驱的帧状态.
     *
     * <p><b>变量槽位合并</b>:从所有前驱状态中选取每个槽位的最新版本,
     * 确保通过任意路径的store操作在主路径中可见.</p>
     *
     * <p><b>操作数栈合并</b>:JVM验证保证在汇合点操作数栈为空
     * (异常处理器例外,其栈上有且仅有一个元素——抛出的异常).
     * 当多个前驱在栈上推送了相同深度的值时,创建stack-PHI节点.</p>
     *
     * @param block        目标基本块
     * @param outputs      各基本块执行后的帧状态映射
     * @param cfg          控制流图
     * @param instructions 正在构建的IR指令列表
     * @param variables    变量列表
     * @return 合并后的帧状态,如果无前驱状态则返回 {@code null}
     */
    private FrameState mergePredecessorStates(BasicBlock block,
                                              Map<BasicBlock, FrameState> outputs,
                                              ControlFlowGraph cfg,
                                              List<IrInstruction> instructions,
                                              List<Variable> variables) {
        List<BasicBlock> preds = cfg.predecessorsOf(block);
        if (preds.isEmpty()) {
            return null;
        }
        if (preds.size() == 1) {
            return outputs.get(preds.get(0));
        }

        // 收集所有前驱状态
        List<FrameState> predStates = new ArrayList<>();
        for (BasicBlock pred : preds) {
            FrameState state = outputs.get(pred);
            if (state != null) {
                predStates.add(state);
            }
        }
        if (predStates.isEmpty()) {
            return null;
        }
        if (predStates.size() == 1) {
            return predStates.get(0).copy();
        }

        // 确定所有前驱中的最大局部变量槽位数
        int maxLocals = 0;
        for (FrameState s : predStates) {
            maxLocals = Math.max(maxLocals, s.locals().length);
        }

        // 合并局部变量:对每个槽位选所有前驱中版本号最大的变量
        // 这确保通过任意路径的store操作贡献其最新变量版本
        Value[] mergedLocals = new Value[maxLocals];
        for (int slot = 0; slot < maxLocals; slot++) {
            Variable best = null;
            for (FrameState s : predStates) {
                if (slot < s.locals().length && s.locals()[slot] instanceof Variable v) {
                    if (best == null || v.version() > best.version()) {
                        best = v;
                    }
                }
            }
            mergedLocals[slot] = best;
        }

        // 合并操作数栈:检测是否有前驱是异常边.
        // 异常处理器的栈 = [thrown_exception];普通汇合点栈为空(JVM验证保证).
        boolean hasExceptionEdge = false;
        for (BasicBlock pred : preds) {
            for (var edge : cfg.incomingOf(block)) {
                if (edge.source().equals(pred)
                        && edge.kind() == com.bingbaihanji.bdec.cfg.EdgeKind.EXCEPTION) {
                    hasExceptionEdge = true;
                    break;
                }
            }
            if (hasExceptionEdge) {
                break;
            }
        }

        Deque<Value> mergedStack;
        if (hasExceptionEdge && !predStates.get(0).stack().isEmpty()) {
            // 异常处理器:保留异常引用在栈上
            mergedStack = new ArrayDeque<>(predStates.get(0).stack());
        } else if (!hasExceptionEdge) {
            // 多前驱普通汇合:检查所有前驱推送的值深度是否相同.
            // 若相同则创建stack-PHI节点.
            boolean allSameDepth = true;
            int depth = predStates.get(0).stack().size();
            for (FrameState ps : predStates) {
                if (ps.stack().size() != depth) {
                    allSameDepth = false;
                    break;
                }
            }
            if (allSameDepth && depth > 0) {
                // 为每个栈槽位创建PHI
                Deque<Value> phiStack = new ArrayDeque<>();
                // 从底到顶构建栈
                List<List<Value>> slotValues = new ArrayList<>();
                for (int i = 0; i < depth; i++) {
                    slotValues.add(new ArrayList<>());
                }
                for (FrameState ps : predStates) {
                    int si = 0;
                    for (Value v : ps.stack()) {
                        slotValues.get(si++).add(v);
                    }
                }
                for (int si = 0; si < depth; si++) {
                    List<Value> phiOps = slotValues.get(si);
                    JavaType phiType = phiOps.get(0).type();
                    IrInstruction phi = new IrInstruction(nextId(), IrOpcode.PHI,
                            phiType, phiOps, -1, block.id());
                    instructions.add(phi);
                    phi.setResultValue(new InstructionRef(phi, phiType));
                    phiStack.addLast(new InstructionRef(phi, phiType));
                }
                mergedStack = phiStack;
            } else {
                mergedStack = new ArrayDeque<>();
            }
        } else {
            // 正常汇合点:根据JVM验证,栈必须为空
            mergedStack = new ArrayDeque<>();
        }

        return new FrameState(mergedStack, mergedLocals);
    }

    /**
     * 对一个基本块进行符号执行.每条操作码分发到对应类别的处理函数,
     * 处理函数会保留字节码元数据,供下游进行运算符和名称解析.
     */
    private FrameState simulateBlock(BasicBlock block, FrameState entry,
                                     List<IrInstruction> instructions,
                                     List<Variable> variables,
                                     ControlFlowGraph cfg, MethodModel method,
                                     ConstantPoolEntry[] cp,
                                     java.util.Map<Integer, String> lvtNames) {
        this.currentLvtNames = lvtNames;
        this.currentMethod = method;
        Deque<Value> stack = new ArrayDeque<>(entry.stack());
        Value[] locals = entry.locals().clone();

        int blockId = block.id();

        for (Instruction insn : block.instructions()) {
            int offset = insn.offset();
            Opcode op;
            try {
                op = Opcode.byCode(insn.opcode());
            } catch (IllegalArgumentException e) {
                continue;
            }

            // ── 按类别分发到处理函数 ──────────────
            switch (op) {
                // 栈操作
                case NOP -> {
                }
                case POP, POP2 -> handlePop(op, stack);
                case DUP, DUP_X1, DUP_X2, DUP2, SWAP -> handleDup(op, stack);

                // 常量
                case ACONST_NULL, ICONST_M1, ICONST_0, ICONST_1, ICONST_2,
                     ICONST_3, ICONST_4, ICONST_5, LCONST_0, LCONST_1,
                     FCONST_0, FCONST_1, FCONST_2, DCONST_0, DCONST_1,
                     BIPUSH, SIPUSH -> handleConstant(op, insn, stack, instructions, offset, blockId);
                case LDC, LDC_W, LDC2_W -> handleLdc(insn, stack, cp, instructions, offset, blockId);

                // 加载
                case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3,
                     LLOAD, LLOAD_0, LLOAD_1, LLOAD_2, LLOAD_3,
                     FLOAD, FLOAD_0, FLOAD_1, FLOAD_2, FLOAD_3,
                     DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3,
                     ALOAD, ALOAD_0, ALOAD_1, ALOAD_2, ALOAD_3 ->
                        handleLoad(op, insn, stack, locals, variables, instructions, offset, blockId);

                // 存储
                case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3,
                     LSTORE, LSTORE_0, LSTORE_1, LSTORE_2, LSTORE_3,
                     FSTORE, FSTORE_0, FSTORE_1, FSTORE_2, FSTORE_3,
                     DSTORE, DSTORE_0, DSTORE_1, DSTORE_2, DSTORE_3,
                     ASTORE, ASTORE_0, ASTORE_1, ASTORE_2, ASTORE_3 ->
                        handleStore(op, insn, stack, locals, variables, instructions, offset, blockId);

                // 整型递增
                case IINC -> handleIinc(insn, variables, instructions, offset, blockId, locals);

                // 算术运算(int)
                case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR ->
                        handleArithmetic(op, stack, instructions, JavaType.INT, offset, blockId);
                case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR ->
                        handleArithmetic(op, stack, instructions, JavaType.LONG, offset, blockId);
                case FADD, FSUB, FMUL, FDIV, FREM ->
                        handleArithmetic(op, stack, instructions, JavaType.FLOAT, offset, blockId);
                case DADD, DSUB, DMUL, DDIV, DREM ->
                        handleArithmetic(op, stack, instructions, JavaType.DOUBLE, offset, blockId);
                case INEG, LNEG, FNEG, DNEG -> handleNegate(op, stack, instructions, offset, blockId);

                // 比较 —— 传入op以保留字节码用于运算符推断
                case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> handleComparison(op, stack, instructions, offset, blockId);

                // 返回
                case RETURN -> instructions.add(IrInstruction.returnInsn(nextId(), null, offset, blockId));
                case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN ->
                        instructions.add(IrInstruction.returnInsn(nextId(), stack.pop(), offset, blockId));

                // 字段 —— 传入 insn+cp 用于名称解析
                case GETSTATIC, GETFIELD -> handleFieldLoad(op, insn, stack, instructions, cp, offset, blockId);
                case PUTSTATIC, PUTFIELD -> handleFieldStore(op, insn, stack, instructions, cp, offset, blockId);

                // 方法调用
                case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE ->
                        handleInvoke(op, insn, stack, instructions, cp, offset, blockId);
                case INVOKEDYNAMIC -> handleInvokeDynamic(insn, stack, instructions, cp, offset, blockId);

                // 对象 / 数组
                case NEW -> handleNew(insn, stack, instructions, cp, offset, blockId);
                case NEWARRAY -> handleNewPrimitiveArray(insn, stack, instructions, offset, blockId);
                case ANEWARRAY -> handleNewArray(op, insn, stack, instructions, cp, offset, blockId);
                case ARRAYLENGTH -> handleArrayLength(stack, instructions, offset, blockId);
                // 数组元素加载:弹出索引,弹出数组 → 压入元素
                case IALOAD, BALOAD, CALOAD, SALOAD ->
                        handleArrayLoad(stack, instructions, JavaType.INT, offset, blockId, op.code());
                case LALOAD -> handleArrayLoad(stack, instructions, JavaType.LONG, offset, blockId, op.code());
                case FALOAD -> handleArrayLoad(stack, instructions, JavaType.FLOAT, offset, blockId, op.code());
                case DALOAD -> handleArrayLoad(stack, instructions, JavaType.DOUBLE, offset, blockId, op.code());
                case AALOAD -> handleArrayLoad(stack, instructions,
                        JavaType.classType("java/lang/Object"), offset, blockId, op.code());
                // 数组元素存储:弹出值,弹出索引,弹出数组
                case IASTORE, BASTORE, CASTORE, SASTORE ->
                        handleArrayStore(stack, instructions, JavaType.INT, offset, blockId, op.code());
                case LASTORE -> handleArrayStore(stack, instructions, JavaType.LONG, offset, blockId, op.code());
                case FASTORE -> handleArrayStore(stack, instructions, JavaType.FLOAT, offset, blockId, op.code());
                case DASTORE -> handleArrayStore(stack, instructions, JavaType.DOUBLE, offset, blockId, op.code());
                case AASTORE -> handleArrayStore(stack, instructions,
                        JavaType.classType("java/lang/Object"), offset, blockId, op.code());

                // 类型检测/转换
                case CHECKCAST -> handleCheckCast(op, insn, stack, instructions, cp, offset, blockId);
                case INSTANCEOF -> handleInstanceOf(op, insn, stack, instructions, cp, offset, blockId);

                // 类型转换 —— 传入 op.code() 用于转换类型推断
                case I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D,
                     D2I, D2L, D2F, I2B, I2C, I2S -> handleConversion(op, stack, instructions, offset, blockId);

                // 监视器 —— 发射IR指令以便SynchronizedRecognizer能检测到
                case MONITORENTER -> {
                    Value obj = stack.isEmpty() ? ConstantValue.NULL : stack.pop();
                    instructions.add(new IrInstruction(nextId(), IrOpcode.MONITOR_ENTER,
                            JavaType.VOID, List.of(obj), offset, blockId, op.code(), null));
                }
                case MONITOREXIT -> {
                    Value obj = stack.isEmpty() ? ConstantValue.NULL : stack.pop();
                    instructions.add(new IrInstruction(nextId(), IrOpcode.MONITOR_EXIT,
                            JavaType.VOID, List.of(obj), offset, blockId, op.code(), null));
                }

                // 分支 —— 传入 op 用于比较运算符推断
                case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                     IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE,
                     IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE ->
                        handleCondition(op, stack, instructions, offset, blockId);
                case IFNULL, IFNONNULL -> handleNullCheck(op, stack, instructions, offset, blockId);
                case GOTO, GOTO_W -> {
                } // 控制流图处理这些
                case MULTIANEWARRAY -> handleMultiNewArray(insn, stack, instructions, offset, blockId);
                case ATHROW -> instructions.add(new IrInstruction(nextId(), IrOpcode.THROW,
                        JavaType.VOID, List.of(stack.pop()), offset, blockId, op.code(), null));

                // Switch —— 弹出key,控制流图处理目标
                case TABLESWITCH, LOOKUPSWITCH -> {
                    if (!stack.isEmpty()) {
                        Value key = stack.pop();
                        instructions.add(new IrInstruction(nextId(), IrOpcode.SWITCH,
                                JavaType.VOID, List.of(key), offset, blockId, op.code(), null));
                    }
                }

                default -> {
                } // 跳过未知操作码
            }
        }

        return new FrameState(stack, locals);
    }

    /**
     * 处理POP/POP2操作码,从操作数栈弹出值.
     */
    private void handlePop(Opcode op, Deque<Value> stack) {
        if (stack.isEmpty()) {
            return;
        }
        stack.pop();
        if (op == Opcode.POP2 && !stack.isEmpty()) {
            stack.pop();
        }
    }

    // ── 常量 ───────────────────────────────────────────────────────

    /**
     * 处理DUP系列操作码,复制或交换操作数栈上的值.
     */
    private void handleDup(Opcode op, Deque<Value> stack) {
        switch (op) {
            case DUP -> {
                if (stack.isEmpty()) {
                    return;
                }
                Value v = stack.pop();
                stack.push(v);
                stack.push(v);
            }
            case DUP_X1 -> {
                if (stack.size() < 2) {
                    return;
                }
                Value v1 = stack.pop(), v2 = stack.pop();
                stack.push(v1);
                stack.push(v2);
                stack.push(v1);
            }
            case DUP_X2 -> {
                if (stack.size() < 3) {
                    return;
                }
                Value v1 = stack.pop(), v2 = stack.pop(), v3 = stack.pop();
                stack.push(v1);
                stack.push(v3);
                stack.push(v2);
                stack.push(v1);
            }
            case DUP2 -> {
                if (stack.isEmpty()) {
                    return;
                }
                Value v1 = stack.pop();
                if (isCategory2(v1)) {
                    // 栈顶值为long/double(类别2)——只复制该项
                    stack.push(v1);
                    stack.push(v1);
                } else if (!stack.isEmpty()) {
                    // 栈顶两个值均为类别1——复制两个
                    Value v2 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                } else {
                    stack.push(v1);
                    stack.push(v1);
                }
            }
            case SWAP -> {
                if (stack.size() < 2) {
                    return;
                }
                Value v1 = stack.pop(), v2 = stack.pop();
                stack.push(v1);
                stack.push(v2);
            }
        }
    }

    /**
     * 处理各种iconst/lconst/fconst/dconst和bipush/sipush常量加载.
     * 发射CONST IR指令使值能跨基本块边界通过InstructionRef链引用.
     */
    private void handleConstant(Opcode op, Instruction insn, Deque<Value> stack,
                                List<IrInstruction> instructions, int offset, int blockId) {
        ConstantValue cv = switch (op) {
            case ACONST_NULL -> ConstantValue.NULL;
            case ICONST_M1 -> new ConstantValue(-1, JavaType.INT);
            case ICONST_0 -> new ConstantValue(0, JavaType.INT);
            case ICONST_1 -> new ConstantValue(1, JavaType.INT);
            case ICONST_2 -> new ConstantValue(2, JavaType.INT);
            case ICONST_3 -> new ConstantValue(3, JavaType.INT);
            case ICONST_4 -> new ConstantValue(4, JavaType.INT);
            case ICONST_5 -> new ConstantValue(5, JavaType.INT);
            case LCONST_0 -> new ConstantValue(0L, JavaType.LONG);
            case LCONST_1 -> new ConstantValue(1L, JavaType.LONG);
            case FCONST_0 -> new ConstantValue(0.0f, JavaType.FLOAT);
            case FCONST_1 -> new ConstantValue(1.0f, JavaType.FLOAT);
            case FCONST_2 -> new ConstantValue(2.0f, JavaType.FLOAT);
            case DCONST_0 -> new ConstantValue(0.0, JavaType.DOUBLE);
            case DCONST_1 -> new ConstantValue(1.0, JavaType.DOUBLE);
            case BIPUSH, SIPUSH -> {
                int val = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
                yield new ConstantValue(val, JavaType.INT);
            }
            default -> null;
        };
        if (cv != null) {
            // 发射CONST IR指令使值能通过InstructionRef链跨基本块边界引用
            IrInstruction constInsn = new IrInstruction(nextId(), IrOpcode.CONST,
                    cv.type(), List.of(cv), offset, blockId);
            instructions.add(constInsn);
            constInsn.setResultValue(new InstructionRef(constInsn, cv.type()));
            stack.push(new InstructionRef(constInsn, cv.type()));
        }
    }

    // ── 加载 ──────────────────────────────────────────────────────

    /**
     * 处理LDC/LDC_W/LDC2_W常量池加载指令.
     * 发射CONST IR指令使值能通过InstructionRef链引用.
     */
    private void handleLdc(Instruction insn, Deque<Value> stack, ConstantPoolEntry[] cp,
                           List<IrInstruction> instructions, int offset, int blockId) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        ConstantValue cv = cpIdx > 0 && cpIdx < cp.length
                ? cpValue(cp[cpIdx], cp)
                : new ConstantValue("?", JavaType.classType("java/lang/Object"));
        // 发射CONST IR指令使值能通过InstructionRef链引用
        IrInstruction constInsn = new IrInstruction(nextId(), IrOpcode.CONST,
                cv.type(), List.of(cv), offset, blockId);
        instructions.add(constInsn);
        constInsn.setResultValue(new InstructionRef(constInsn, cv.type()));
        stack.push(new InstructionRef(constInsn, cv.type()));
    }

    /**
     * 处理局部变量加载指令(ILOAD,ALOAD等).
     * 确保变量携带其局部变量表名称,即使来自前驱帧状态的变量也如此.
     */
    private void handleLoad(Opcode op, Instruction insn, Deque<Value> stack,
                            Value[] locals, List<Variable> variables,
                            List<IrInstruction> instructions, int offset, int blockId) {
        int idx = varIndex(insn, op);
        JavaType type = loadType(op);
        Value v = locals[idx];
        if (v == null) {
            v = lookupReadVar(variables, idx, type, offset);
        }
        // 确保变量携带其局部变量表名称(即使来自前驱帧状态)
        if (v instanceof Variable var && currentLvtNames.containsKey(idx)
                && (var.name() == null || var.name().startsWith("var"))) {
            var.setName(currentLvtNames.get(idx));
        }
        emitLoad(v, instructions, offset, blockId);
        stack.push(v);
    }

    // ── 存储 ──────────────────────────────────────────────────────

    /**
     * 根据加载操作码确定对应类型.
     */
    private JavaType loadType(Opcode op) {
        return switch (op) {
            case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3 -> JavaType.INT;
            case LLOAD, LLOAD_0, LLOAD_1, LLOAD_2, LLOAD_3 -> JavaType.LONG;
            case FLOAD, FLOAD_0, FLOAD_1, FLOAD_2, FLOAD_3 -> JavaType.FLOAT;
            case DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3 -> JavaType.DOUBLE;
            default -> JavaType.classType("java/lang/Object");
        };
    }

    // ── IINC ──────────────────────────────────────────────────────

    /**
     * 处理局部变量存储指令(ISTORE,ASTORE等).
     * 每次存储都创建一个新版本变量,防止槽位混淆(如"this"与重用槽位的临时变量).
     * 将新变量写入locals数组,确保后续LOAD指令找到Variable而非原始InstructionRef,
     * 从而防止错误的表达式展开.
     */
    private void handleStore(Opcode op, Instruction insn, Deque<Value> stack,
                             Value[] locals, List<Variable> variables,
                             List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        int idx = varIndex(insn, op);
        Value val = stack.pop();
        // 每次存储都创建一个新版本——防止"this"槽位混淆
        Variable var = createWriteVar(variables, idx, val.type(), offset);
        // 将新Variable存入locals数组确保后续LOAD找到Variable而非原始InstructionRef.
        // 这能防止错误的表达式展开,例如:
        // "n = cap - 1 | cap - 1 >>> 1" → "n = n | n >>> 1"(错误!)
        locals[idx] = var;
        instructions.add(IrInstruction.store(nextId(), var, val, offset, blockId));
    }

    // ── 算术 ─────────────────────────────────────────────────────

    /**
     * 处理IINC(整型递增)指令.
     * 读取当前值并写入新值——为写入创建新版本变量,
     * 并更新locals数组以确保同一块内后续LOAD看到新版本.
     */
    private void handleIinc(Instruction insn, List<Variable> variables,
                            List<IrInstruction> instructions, int offset, int blockId,
                            Value[] locals) {
        int idx = varIndex(insn, Opcode.IINC);
        int incr = insn.rawOperands().size() > 1 ? insn.rawOperands().get(1) : 0;
        // IINC读取当前值并写入新值——创建新版本变量
        Variable readVar = lookupReadVar(variables, idx, JavaType.INT, offset);
        Variable writeVar = createWriteVar(variables, idx, JavaType.INT, offset);
        // 更新locals数组以便同一块内后续LOAD看到新版本
        if (idx < locals.length) {
            locals[idx] = writeVar;
        }
        instructions.add(new IrInstruction(nextId(), IrOpcode.INC, JavaType.INT,
                List.of(readVar, writeVar, new ConstantValue(incr, JavaType.INT)),
                offset, blockId));
    }

    /**
     * 处理算术二元运算(IADD,ISUB等).
     * 弹出右操作数和左操作数(栈顶为右),创建BINARY IR指令并压回结果.
     */
    private void handleArithmetic(Opcode op, Deque<Value> stack,
                                  List<IrInstruction> instructions,
                                  JavaType type, int offset, int blockId) {
        if (stack.size() < 2) {
            return;
        }
        Value right = stack.pop();
        Value left = stack.pop();
        IrInstruction bin = IrInstruction.binary(nextId(), IrOpcode.BINARY, left, right,
                type, offset, blockId, op.code());
        instructions.add(bin);
        bin.setResultValue(new InstructionRef(bin, type));
        stack.push(new InstructionRef(bin, type));
    }

    // ── 比较 ──────────────────────────────────────────────────────

    /**
     * 处理取负操作(INEG,LNEG等一元运算).
     */
    private void handleNegate(Opcode op, Deque<Value> stack,
                              List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        IrInstruction un = new IrInstruction(nextId(), IrOpcode.UNARY, v.type(), List.of(v),
                offset, blockId, op.code(), null);
        instructions.add(un);
        un.setResultValue(new InstructionRef(un, v.type()));
        stack.push(new InstructionRef(un, v.type()));
    }

    // ── 字段 ─────────────────────────────────────────────────────

    /**
     * 处理比较操作(lcmp,fcmpl,fcmpg,dcmpl,dcmpg).
     * 创建COMPARE IR指令,结果类型为int.
     */
    private void handleComparison(Opcode op, Deque<Value> stack,
                                  List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.size() < 2) {
            return;
        }
        Value right = stack.pop();
        Value left = stack.pop();
        IrInstruction cmp = IrInstruction.binary(nextId(), IrOpcode.COMPARE, left, right,
                JavaType.INT, offset, blockId, op.code());
        instructions.add(cmp);
        cmp.setResultValue(new InstructionRef(cmp, JavaType.INT));
        stack.push(new InstructionRef(cmp, JavaType.INT));
    }

    /**
     * 处理字段加载(GETFIELD/GETSTATIC).
     * 解析字段名和类型,创建FIELD_LOAD IR指令.
     * 对于GETSTATIC,标记声明类信息以便BlockReducer输出完整限定名.
     */
    private void handleFieldLoad(Opcode op, Instruction insn, Deque<Value> stack,
                                 List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                 int offset, int blockId) {
        Value obj = (op == Opcode.GETFIELD && !stack.isEmpty()) ? stack.pop() : null;
        String fieldName = resolveFieldName(insn, cp);
        JavaType fieldType = resolveFieldType(insn, cp);
        IrInstruction fi = IrInstruction.fieldLoad(nextId(), obj,
                fieldType, offset, blockId, fieldName);
        instructions.add(fi);
        fi.setResultValue(new InstructionRef(fi, fi.resultType()));

        // 对于静态字段访问(GETSTATIC),标记声明类信息,
        // 以便BlockReducer输出System.out而非仅out
        if (op == Opcode.GETSTATIC) {
            String declaringClass = resolveFieldDeclaringClass(insn, cp);
            if (declaringClass != null) {
                fi.addAnnotation(com.bingbaihanji.bdec.semantic.SemanticAnnotation.of(
                        com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS,
                        com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS,
                        declaringClass));
            }
        }

        stack.push(new InstructionRef(fi, fi.resultType()));
    }

    /**
     * 处理字段存储(PUTFIELD/PUTSTATIC).
     * 解析字段名,创建FIELD_STORE IR指令.
     */
    private void handleFieldStore(Opcode op, Instruction insn, Deque<Value> stack,
                                  List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                  int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value val = stack.pop();
        Value obj = (op == Opcode.PUTFIELD && !stack.isEmpty()) ? stack.pop() : null;
        String fieldName = resolveFieldName(insn, cp);
        instructions.add(IrInstruction.fieldStore(nextId(), obj, val, offset, blockId, fieldName));
    }

    /**
     * 通过字段引用指令从常量池解析字段类型.
     */
    private JavaType resolveFieldType(Instruction insn, ConstantPoolEntry[] cp) {
        if (insn.rawOperands().isEmpty()) {
            return JavaType.classType("java/lang/Object");
        }
        int cpIdx = insn.rawOperands().get(0);
        if (cpIdx <= 0 || cpIdx >= cp.length) {
            return JavaType.classType("java/lang/Object");
        }
        try {
            ConstantPoolEntry entry = cp[cpIdx];
            int natIdx = switch (entry) {
                case ConstantPoolEntry.CpFieldRef fr -> fr.nameAndTypeIndex();
                default -> -1;
            };
            if (natIdx > 0 && natIdx < cp.length
                    && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                String desc = ConstantPoolParser.utf8(cp, nat.descriptorIndex());
                return com.bingbaihanji.bdec.type.TypeResolver.parseFieldType(desc);
            }
        } catch (Exception ignored) {
            // 解析失败则返回默认类型
        }
        return JavaType.classType("java/lang/Object");
    }

    /**
     * 通过字段引用指令从常量池解析字段名.
     */
    private String resolveFieldName(Instruction insn, ConstantPoolEntry[] cp) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int cpIdx = insn.rawOperands().get(0);
        if (cpIdx <= 0 || cpIdx >= cp.length) {
            return null;
        }
        try {
            ConstantPoolEntry entry = cp[cpIdx];
            int natIdx = switch (entry) {
                case ConstantPoolEntry.CpFieldRef fr -> fr.nameAndTypeIndex();
                default -> -1;
            };
            if (natIdx > 0 && natIdx < cp.length
                    && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                return ConstantPoolParser.utf8(cp, nat.nameIndex());
            }
        } catch (Exception ignored) {
            // 解析失败则返回 null
        }
        return null;
    }

    /**
     * 解析字段引用指令的声明类名(用于GETSTATIC指令).
     */
    private String resolveFieldDeclaringClass(Instruction insn, ConstantPoolEntry[] cp) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int cpIdx = insn.rawOperands().get(0);
        if (cpIdx <= 0 || cpIdx >= cp.length) {
            return null;
        }
        try {
            ConstantPoolEntry entry = cp[cpIdx];
            int classIdx = switch (entry) {
                case ConstantPoolEntry.CpFieldRef fr -> fr.classIndex();
                default -> -1;
            };
            if (classIdx > 0 && classIdx < cp.length) {
                return ConstantPoolParser.className(cp, classIdx);
            }
        } catch (Exception ignored) {
            // 解析失败则返回 null
        }
        return null;
    }

    // ── 方法调用 ───────────────────────────────────────────────────

    /**
     * 通过方法引用指令从常量池解析方法名.
     */
    private String resolveMethodName(Instruction insn, ConstantPoolEntry[] cp) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int cpIdx = insn.rawOperands().get(0);
        if (cpIdx <= 0 || cpIdx >= cp.length) {
            return null;
        }
        try {
            ConstantPoolEntry entry = cp[cpIdx];
            int natIdx = switch (entry) {
                case ConstantPoolEntry.CpMethodRef mr -> mr.nameAndTypeIndex();
                case ConstantPoolEntry.CpInterfaceMethodRef imr -> imr.nameAndTypeIndex();
                default -> -1;
            };
            if (natIdx > 0 && natIdx < cp.length
                    && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                return ConstantPoolParser.utf8(cp, nat.nameIndex());
            }
        } catch (Exception ignored) {
            // 解析失败则返回 null
        }
        return null;
    }

    // ── InvokeDynamic ─────────────────────────────────────────────

    /**
     * 处理常规方法调用(INVOKEVIRTUAL/INVOKESPECIAL/INVOKESTATIC/INVOKEINTERFACE).
     * 从常量池解析参数类型和返回类型,按逆序弹出实参,弹出接收者对象,
     * 创建INVOKE IR指令.对boolean参数折叠0/1常量,对静态调用标记声明类.
     */
    private void handleInvoke(Opcode op, Instruction insn, Deque<Value> stack,
                              List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                              int offset, int blockId) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        int argCount = 0;
        JavaType returnType = JavaType.classType("java/lang/Object");
        String methodName = null;
        String declaringClass = null; // 用于构造函数委托目标
        com.bingbaihanji.bdec.type.JavaType[] paramTypes = null; // 用于boolean折叠

        if (cpIdx > 0 && cpIdx < cp.length) {
            try {
                ConstantPoolEntry cpEntry = cp[cpIdx];
                int natIdx = -1;
                int classIdx = -1;
                switch (cpEntry) {
                    case ConstantPoolEntry.CpMethodRef ref -> {
                        natIdx = ref.nameAndTypeIndex();
                        classIdx = ref.classIndex();
                    }
                    case ConstantPoolEntry.CpInterfaceMethodRef ref -> {
                        natIdx = ref.nameAndTypeIndex();
                        classIdx = ref.classIndex();
                    }
                    default -> {
                    }
                }
                if (classIdx > 0 && classIdx < cp.length) {
                    declaringClass = ConstantPoolParser.className(cp, classIdx);
                }
                if (natIdx > 0 && natIdx < cp.length && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType(int nameIndex, int descriptorIndex)) {
                    String desc = ConstantPoolParser.utf8(cp, descriptorIndex);
                    methodName = ConstantPoolParser.utf8(cp, nameIndex);
                    paramTypes = com.bingbaihanji.bdec.type.TypeResolver.parseMethodParameterTypes(desc);
                    argCount = paramTypes.length;
                    returnType = com.bingbaihanji.bdec.type.TypeResolver.parseMethodReturnType(desc);
                }
            } catch (Exception ignored) {
                // 保持默认值
            }
        }

        // 按逆序弹出实参
        List<Value> args = new ArrayList<>();
        for (int a = 0; a < argCount && !stack.isEmpty(); a++) {
            args.addFirst(stack.pop());
        }
        // 捕获接收者(对于非静态调用)——保留方法调用的目标对象
        Value receiver = null;
        if (op != Opcode.INVOKESTATIC && !stack.isEmpty()) {
            receiver = stack.pop();
        }

        // 折叠boolean常量:当参数为boolean时,将0/1转为false/true.
        // 由于常量现在以CONST IR的形式发射,需沿InstructionRef链追溯.
        if (paramTypes != null) {
            for (int p = 0; p < paramTypes.length && p < args.size(); p++) {
                if (paramTypes[p].kind() == TypeKind.BOOLEAN) {
                    Value arg = args.get(p);
                    ConstantValue cv = unwrapConstant(arg);
                    if (cv != null && cv.value() instanceof Integer i) {
                        args.set(p, new ConstantValue(i != 0, JavaType.BOOLEAN));
                    }
                }
            }
        }

        IrInstruction inv = IrInstruction.invoke(nextId(), receiver, args, returnType,
                offset, blockId, methodName);
        instructions.add(inv);

        // 对静态调用标记声明类(用于Arrays.fill() vs fill()的区分)
        if (op == Opcode.INVOKESTATIC && declaringClass != null) {
            inv.addAnnotation(com.bingbaihanji.bdec.semantic.SemanticAnnotation.of(
                    com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS,
                    com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS,
                    declaringClass));
        }

        // 标记构造函数委托调用,附带目标类信息
        if ("<init>".equals(methodName)) {
            if (declaringClass != null) {
                inv.addAnnotation(com.bingbaihanji.bdec.semantic.SemanticAnnotation.of(
                        com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION,
                        com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_TARGET_CLASS,
                        declaringClass));
            } else {
                inv.addAnnotation(com.bingbaihanji.bdec.semantic.SemanticAnnotation.of(
                        com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION));
            }
        }

        if (returnType.kind() != TypeKind.VOID) {
            inv.setResultValue(new InstructionRef(inv, returnType));
            stack.push(new InstructionRef(inv, returnType));
        }
    }

    // ── 对象 / 数组 ───────────────────────────────────────────────

    /**
     * 处理INVOKEDYNAMIC指令.
     * 解析引导方法信息,创建INVOKE IR指令,并标记INDY语义注解.
     * 引导方法参数用于下游pass检测lambda表达式和方法引用.
     */
    private void handleInvokeDynamic(Instruction insn, Deque<Value> stack,
                                     List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                     int offset, int blockId) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        int argCount = 0;
        JavaType returnType = JavaType.classType("java/lang/Object");
        String methodName = "invokeDynamic";
        String descriptor = "";
        int bootstrapIdx = -1;

        if (cpIdx > 0 && cpIdx < cp.length) {
            try {
                ConstantPoolEntry entry = cp[cpIdx];
                if (entry instanceof ConstantPoolEntry.CpInvokeDynamic(int bootstrapMethodAttrIndex, int natIdx)) {
                    bootstrapIdx = bootstrapMethodAttrIndex;
                    if (natIdx > 0 && natIdx < cp.length
                            && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType(
                            int nameIndex, int descriptorIndex
                    )) {
                        descriptor = ConstantPoolParser.utf8(cp, descriptorIndex);
                        methodName = ConstantPoolParser.utf8(cp, nameIndex);
                        var params = com.bingbaihanji.bdec.type.TypeResolver.parseMethodParameterTypes(descriptor);
                        argCount = params.length;
                        returnType = com.bingbaihanji.bdec.type.TypeResolver.parseMethodReturnType(descriptor);
                    }
                }
            } catch (Exception ignored) {
                // 保持默认值
            }
        }

        // 弹出实参
        List<Value> args = new ArrayList<>();
        for (int a = 0; a < argCount && !stack.isEmpty(); a++) {
            args.addFirst(stack.pop());
        }

        IrInstruction inv = IrInstruction.invoke(nextId(), null, args, returnType,
                offset, blockId, methodName);

        // 解析引导方法信息,供下游检测lambda vs 方法引用
        java.util.Map<String, Object> annotProps = new java.util.LinkedHashMap<>();
        annotProps.put("bootstrapIdx", bootstrapIdx);
        annotProps.put("indyName", methodName);
        annotProps.put("descriptor", descriptor);

        if (bootstrapIdx >= 0 && !currentBootstrapMethods.isEmpty()
                && bootstrapIdx < currentBootstrapMethods.size()) {
            try {
                BootstrapMethodEntry bsm = currentBootstrapMethods.get(bootstrapIdx);
                resolveBootstrapMethod(bsm, cp, annotProps);
            } catch (Exception ignored) {
                // 引导方法解析为尽力而为
            }
        }

        inv.addAnnotation(com.bingbaihanji.bdec.semantic.SemanticAnnotation.of(
                com.bingbaihanji.bdec.semantic.SemanticTag.INDY, annotProps));
        instructions.add(inv);
        if (returnType.kind() != TypeKind.VOID) {
            inv.setResultValue(new InstructionRef(inv, returnType));
            stack.push(new InstructionRef(inv, returnType));
        }
    }

    /**
     * 解析引导方法参数,提取实现方法句柄.
     * 对于LambdaMetafactory模式,argument[1]为lambda体/方法引用目标的方法句柄.
     */
    private void resolveBootstrapMethod(BootstrapMethodEntry bsm, ConstantPoolEntry[] cp,
                                        java.util.Map<String, Object> annotProps) {
        java.util.List<Integer> arguments = bsm.arguments();
        if (arguments.size() < 2) {
            return;
        }

        // 参数1为实现方法句柄
        int implHandleIdx = arguments.get(1);
        if (implHandleIdx <= 0 || implHandleIdx >= cp.length) {
            return;
        }

        ConstantPoolEntry implHandleEntry = cp[implHandleIdx];
        if (!(implHandleEntry instanceof ConstantPoolEntry.CpMethodHandle(int refKind, int refIdx))) {
            return;
        }

        annotProps.put("implKind", refKind);

        if (refIdx <= 0 || refIdx >= cp.length) {
            return;
        }

        ConstantPoolEntry refEntry = cp[refIdx];
        int classIdx = -1;
        int natIdx = -1;
        if (refEntry instanceof ConstantPoolEntry.CpMethodRef(int classIndex, int nameAndTypeIndex)) {
            classIdx = classIndex;
            natIdx = nameAndTypeIndex;
        } else if (refEntry instanceof ConstantPoolEntry.CpInterfaceMethodRef(int classIndex, int nameAndTypeIndex)) {
            classIdx = classIndex;
            natIdx = nameAndTypeIndex;
        } else {
            return;
        }

        if (classIdx > 0) {
            String implOwner = ConstantPoolParser.className(cp, classIdx);
            annotProps.put("implOwner", implOwner);
        }

        if (natIdx > 0 && natIdx < cp.length
                && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
            String implName = ConstantPoolParser.utf8(cp, nat.nameIndex());
            annotProps.put("implName", implName);
            // 提取实现方法描述符,供 BlockReducer 生成正确的 lambda 参数占位符
            String implDesc = ConstantPoolParser.utf8(cp, nat.descriptorIndex());
            annotProps.put("implDescriptor", implDesc);
        }
    }

    /**
     * 处理NEW对象创建指令.
     * 从常量池解析类名,创建NEW IR指令.
     */
    private void handleNew(Instruction insn, Deque<Value> stack,
                           List<IrInstruction> instructions,
                           ConstantPoolEntry[] cp, int offset, int blockId) {
        // 从常量池解析类类型
        String className = "java/lang/Object";
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        if (cpIdx > 0 && cpIdx < cp.length) {
            className = ConstantPoolParser.className(cp, cpIdx);
            if (className == null) {
                className = "java/lang/Object";
            }
        }
        IrInstruction n = IrInstruction.newInsn(nextId(),
                JavaType.classType(className), offset, blockId);
        instructions.add(n);
        n.setResultValue(new InstructionRef(n, n.resultType()));
        stack.push(new InstructionRef(n, n.resultType()));
    }

    /**
     * 处理NEWARRAY基本类型数组创建指令(new int[10],new byte[20]等).
     * 操作数字节表示数组类型代码(4=boolean, 5=char, 6=float, 7=double,
     * 8=byte, 9=short, 10=int, 11=long).
     */
    private void handleNewPrimitiveArray(Instruction insn, Deque<Value> stack,
                                         List<IrInstruction> instructions,
                                         int offset, int blockId) {
        int typeCode = insn.rawOperands().isEmpty() ? 10 : insn.rawOperands().get(0);
        JavaType elementType = switch (typeCode) {
            case 4 -> JavaType.BOOLEAN;
            case 5 -> JavaType.CHAR;
            case 6 -> JavaType.FLOAT;
            case 7 -> JavaType.DOUBLE;
            case 8 -> JavaType.BYTE;
            case 9 -> JavaType.SHORT;
            case 11 -> JavaType.LONG;
            default -> JavaType.INT; // 10 = T_INT
        };
        Value size = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                elementType, List.of(size), offset, blockId, insn.opcode(), null);
        instructions.add(na);
        na.setResultValue(new InstructionRef(na, na.resultType()));
        stack.push(new InstructionRef(na, na.resultType()));
    }

    /**
     * 处理ANEWARRAY引用类型数组创建指令(new String[10]).
     */
    private void handleNewArray(Opcode op, Instruction insn, Deque<Value> stack,
                                List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                int offset, int blockId) {
        Value size = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
        JavaType elementType = resolveClassType(insn, cp);
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                elementType, List.of(size), offset, blockId, op.code(), null);
        instructions.add(na);
        na.setResultValue(new InstructionRef(na, na.resultType()));
        stack.push(new InstructionRef(na, na.resultType()));
    }

    /**
     * 处理MULTIANEWARRAY多维数组创建指令.
     */
    private void handleMultiNewArray(Instruction insn, Deque<Value> stack,
                                     List<IrInstruction> instructions,
                                     int offset, int blockId) {
        // MULTIANEWARRAY操作数格式:[cp_index, dimensions]
        int dims = 1;
        if (insn.rawOperands().size() > 1) {
            dims = insn.rawOperands().get(1);
        }
        // 从栈中弹出各维度大小
        List<Value> sizes = new ArrayList<>();
        for (int d = 0; d < dims && !stack.isEmpty(); d++) {
            sizes.addFirst(stack.pop());
        }
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                JavaType.classType("java/lang/Object"), sizes, offset, blockId);
        instructions.add(na);
        na.setResultValue(new InstructionRef(na, na.resultType()));
        stack.push(new InstructionRef(na, na.resultType()));
    }

    // ── 数组元素加载/存储 ────────────────────────────────────────

    /**
     * 处理数组元素加载(IALOAD,AALOAD等).
     * 弹出索引和数组引用,创建ARRAY_LOAD IR指令.
     */
    private void handleArrayLoad(Deque<Value> stack, List<IrInstruction> instructions,
                                 JavaType elementType, int offset, int blockId, int opcode) {
        if (stack.size() < 2) {
            return;
        }
        Value index = stack.pop();
        Value arr = stack.pop();
        IrInstruction al = new IrInstruction(nextId(), IrOpcode.ARRAY_LOAD,
                elementType, List.of(arr, index), offset, blockId, opcode, null);
        instructions.add(al);
        al.setResultValue(new InstructionRef(al, elementType));
        stack.push(new InstructionRef(al, elementType));
    }

    /**
     * 处理数组元素存储(IASTORE,AASTORE等).
     * 弹出值,索引和数组引用,创建ARRAY_STORE IR指令.
     */
    private void handleArrayStore(Deque<Value> stack, List<IrInstruction> instructions,
                                  JavaType elementType, int offset, int blockId, int opcode) {
        if (stack.size() < 3) {
            return;
        }
        Value value = stack.pop();
        Value index = stack.pop();
        Value arr = stack.pop();
        IrInstruction ast = new IrInstruction(nextId(), IrOpcode.ARRAY_STORE,
                JavaType.VOID, List.of(arr, index, value), offset, blockId, opcode, null);
        instructions.add(ast);
    }

    // ── 类型 / 转换 ───────────────────────────────────────────────

    /**
     * 处理ARRAYLENGTH数组长度获取指令.
     */
    private void handleArrayLength(Deque<Value> stack, List<IrInstruction> instructions,
                                   int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value arr = stack.pop();
        IrInstruction al = new IrInstruction(nextId(), IrOpcode.ARRAY_LENGTH,
                JavaType.INT, List.of(arr), offset, blockId);
        instructions.add(al);
        al.setResultValue(new InstructionRef(al, JavaType.INT));
        stack.push(new InstructionRef(al, JavaType.INT));
    }

    /**
     * 处理CHECKCAST类型检查转换指令.
     */
    private void handleCheckCast(Opcode op, Instruction insn, Deque<Value> stack,
                                 List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                 int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        // 从常量池解析目标类型
        JavaType targetType = resolveClassType(insn, cp);
        IrInstruction c = IrInstruction.cast(nextId(), v, targetType, offset, blockId, op.code());
        instructions.add(c);
        c.setResultValue(new InstructionRef(c, c.resultType()));
        stack.push(new InstructionRef(c, c.resultType()));
    }

    /**
     * 处理INSTANCEOF类型检测指令.
     * nameHint携带目标类内部名,供BlockReducer使用.
     */
    private void handleInstanceOf(Opcode op, Instruction insn, Deque<Value> stack,
                                  List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                  int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value obj = stack.pop(); // 保留对象作为操作数
        JavaType targetType = resolveClassType(insn, cp);
        IrInstruction io = new IrInstruction(nextId(), IrOpcode.INSTANCE_OF,
                JavaType.INT, List.of(obj), offset, blockId, op.code(), targetType.internalName());
        io.setResultValue(new InstructionRef(io, JavaType.INT));
        instructions.add(io);
        stack.push(new InstructionRef(io, JavaType.INT));
    }

    /**
     * 从引用常量池的指令(checkcast,instanceof,anewarray)中解析类类型.
     */
    private JavaType resolveClassType(Instruction insn, ConstantPoolEntry[] cp) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        if (cpIdx > 0 && cpIdx < cp.length) {
            String className = ConstantPoolParser.className(cp, cpIdx);
            if (className != null) {
                return JavaType.classType(className);
            }
        }
        return JavaType.classType("java/lang/Object");
    }

    // ── 分支 ──────────────────────────────────────────────────────

    /**
     * 处理类型转换指令(I2L,F2I等).
     */
    private void handleConversion(Opcode op, Deque<Value> stack,
                                  List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        JavaType to = targetType(op);
        IrInstruction conv = IrInstruction.cast(nextId(), v, to, offset, blockId, op.code());
        instructions.add(conv);
        conv.setResultValue(new InstructionRef(conv, to));
        stack.push(new InstructionRef(conv, to));
    }

    /**
     * 处理条件分支指令(if系列).
     * 区分零值比较(IFEQ..IFLE:单操作数与0比较)
     * 和整数比较(IF_ICMPxx:双操作数比较).
     * 对于IFxx系列,栈上有且仅有一个值,将其作为左操作数与0比较.
     * 这样IFEQ生成的比较就正确地表现为 "capacity > 0".
     */
    private void handleCondition(Opcode op, Deque<Value> stack,
                                 List<IrInstruction> instructions, int offset, int blockId) {
        boolean isIfCmp = switch (op) {
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                 IF_ACMPEQ, IF_ACMPNE -> true;
            default -> false;
        };
        if (isIfCmp) {
            // 双操作数弹出:先右后左(标准JVM语义)
            Value right = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            Value left = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                    JavaType.INT, List.of(left, right), offset, blockId, op.code(), null));
        } else {
            // IFxx —— 单操作数:栈上的值与0比较.
            // 将值放在左边,0放在右边,使IFGT等产生正确的 "capacity > 0" 语义
            Value val = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                    JavaType.INT, List.of(val, new ConstantValue(0, JavaType.INT)),
                    offset, blockId, op.code(), null));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  共享辅助方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 处理空值检查指令(IFNULL/IFNONNULL).
     */
    private void handleNullCheck(Opcode op, Deque<Value> stack, List<IrInstruction> instructions,
                                 int offset, int blockId) {
        Value ref = !stack.isEmpty() ? stack.pop() : ConstantValue.NULL;
        instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                JavaType.INT, List.of(ref, ConstantValue.NULL), offset, blockId, op.code(), null));
    }

    /** 获取下一条指令的唯一ID. */
    private int nextId() {return nextInsnId++;}

    /**
     * 获取指令操作的变量索引.
     * 优先使用指令级别varIndex(由WIDE或显式操作数解码设置),
     * 其次使用操作码隐式索引(例如 iload_0 -> 0),最后使用首个原始操作数.
     */
    private int varIndex(Instruction insn, Opcode op) {
        if (insn.varIndex() >= 0 && insn.opcode() == op.code()) {
            return insn.varIndex();
        }
        // 仅对无显式操作数的操作码使用隐式变量索引
        // (例如 ILOAD_0 -> 0, ISTORE_3 -> 3).
        // 对于带显式索引的操作码(如含操作数字节的ILOAD),implicitVarIndex为0但这是错误的.
        if (op.implicitVarIndex() >= 0 && op.operandBytes() == 0) {
            return op.implicitVarIndex();
        }
        if (!insn.rawOperands().isEmpty()) {
            return insn.rawOperands().get(0);
        }
        return 0;
    }

    /**
     * 获取或创建指定槽位和类型的变量(版本0).
     */
    private Variable getOrCreateVar(List<Variable> variables, int slot, JavaType type, boolean isParam) {
        for (Variable v : variables) {
            if (v.slot() == slot && v.version() == 0) {
                return v;
            }
        }
        Variable v = new Variable(slot, 0, type, isParam, slot);
        variables.add(v);
        return v;
    }

    /**
     * 为STORE操作创建一个新版本的变量(槽位正在被写入).
     * 这防止槽位0('this')与存储到槽位0的临时变量相混淆.
     * 同时将isParameter标志从版本0传播到所有新版本,防止BlockReducer
     * 为参数重赋值(如"int dest = 0"遮盖了参数"int[] dest")生成VariableDeclaration.
     */
    private Variable createWriteVar(List<Variable> variables, int slot, JavaType type,
                                      int offset) {
        int maxVersion = 0;
        boolean isParam = false;
        for (Variable v : variables) {
            if (v.slot() == slot) {
                maxVersion = Math.max(maxVersion, v.version());
                // 将isParameter从版本0传播到所有新版本.
                // 这防止BlockReducer为参数重赋值生成VariableDeclaration.
                if (v.isParameter() && v.version() == 0) {
                    isParam = true;
                }
            }
        }
        Variable v = new Variable(slot, maxVersion + 1, type, isParam, slot);
        // 向前传播局部变量表名称以便新版本保留原始参数名.
        // 但是跳过实例方法中的槽位0:'this'仅版本0有效;
        // 对槽位0的写入是一个重用该槽位的不同变量.
        // 使用作用域感知查找:优先按 PC 查找 LVT 条目,
        // 回退到 flat map(向后兼容旧 MethodModel)
        String lvtName = currentMethod.lookupVarName(slot, offset);
        if (lvtName == null) {
            lvtName = currentLvtNames.get(slot);
        }
        if (lvtName != null
                && !(slot == 0 && maxVersion > 0)) {
            v.setName(lvtName);
        }
        variables.add(v);
        return v;
    }

    /**
     * 获取指定槽位最新版本的变量(用于LOAD指令).
     * 如果尚无该槽位的变量,则创建版本0的新变量,并应用局部变量表名称.
     */
    private Variable lookupReadVar(List<Variable> variables, int slot, JavaType type,
                                   int offset) {
        Variable latest = null;
        for (Variable v : variables) {
            if (v.slot() == slot && (latest == null || v.version() > latest.version())) {
                latest = v;
            }
        }
        if (latest != null) {
            return latest;
        }
        // 首次访问:创建版本0,使用作用域感知查找 LVT 名称
        Variable v = new Variable(slot, 0, type, false, slot);
        String lvtName = currentMethod.lookupVarName(slot, offset);
        if (lvtName == null) {
            lvtName = currentLvtNames.get(slot);
        }
        if (lvtName != null) {
            v.setName(lvtName);
        }
        variables.add(v);
        return v;
    }

    /**
     * 将值转换为Variable,如果不是Variable则创建新变量.
     */
    @SuppressWarnings("unused")
    private Variable asVar(Value v, List<Variable> variables, int slot) {
        if (v instanceof Variable var) {
            return var;
        }
        return getOrCreateVar(variables, slot, v.type(), false);
    }

    /**
     * 发射LOAD IR指令(如果值是Variable类型).
     */
    private void emitLoad(Value v, List<IrInstruction> instructions, int offset, int blockId) {
        if (v instanceof Variable var) {
            IrInstruction load = IrInstruction.load(nextId(), var, offset, blockId);
            instructions.add(load);
            load.setResultValue(new InstructionRef(load, v.type()));
        }
    }

    /**
     * 将常量池条目转换为对应的ConstantValue.
     */
    private ConstantValue cpValue(ConstantPoolEntry entry, ConstantPoolEntry[] pool) {
        return switch (entry) {
            case ConstantPoolEntry.CpInteger i -> new ConstantValue(i.value(), JavaType.INT);
            case ConstantPoolEntry.CpFloat f -> new ConstantValue(f.value(), JavaType.FLOAT);
            case ConstantPoolEntry.CpLong l -> new ConstantValue(l.value(), JavaType.LONG);
            case ConstantPoolEntry.CpDouble d -> new ConstantValue(d.value(), JavaType.DOUBLE);
            case ConstantPoolEntry.CpString s -> new ConstantValue(
                    ConstantPoolParser.utf8(pool, s.stringIndex()),
                    JavaType.classType("java/lang/String"));
            case ConstantPoolEntry.CpClass c -> new ConstantValue(
                    ConstantPoolParser.utf8(pool, c.nameIndex()),
                    JavaType.classType("java/lang/Class"));
            default -> new ConstantValue("<cp:" + entry.tag() + ">", JavaType.classType("java/lang/Object"));
        };
    }

    /**
     * 根据类型转换操作码确定目标类型.
     */
    private JavaType targetType(Opcode op) {
        return switch (op) {
            case I2L -> JavaType.LONG;
            case I2F -> JavaType.FLOAT;
            case I2D -> JavaType.DOUBLE;
            case L2I -> JavaType.INT;
            case L2F -> JavaType.FLOAT;
            case L2D -> JavaType.DOUBLE;
            case F2I -> JavaType.INT;
            case F2L -> JavaType.LONG;
            case F2D -> JavaType.DOUBLE;
            case D2I -> JavaType.INT;
            case D2L -> JavaType.LONG;
            case D2F -> JavaType.FLOAT;
            case I2B -> JavaType.BYTE;
            case I2C -> JavaType.CHAR;
            case I2S -> JavaType.SHORT;
            default -> JavaType.INT;
        };
    }
}
