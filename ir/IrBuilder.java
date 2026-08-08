package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
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
 * Stack simulation engine — converts bytecode (stack-based) to LinearIr (register-based).
 *
 * Each JVM instruction is processed by simulating its effect on an operand stack
 * and local variable array. The main {@link #simulateBlock} switch dispatches to
 * category-specific handler methods.
 *
 * Metadata (original bytecode opcode, field/method names) is preserved in
 * {@link IrInstruction} so downstream passes can emit correct operators and names.
 */
public final class IrBuilder {

    private int nextInsnId = 0;

    private int nextVarId = 0;

    /** Current method's LVT names (slot → name), set during simulateBlock. */
    private java.util.Map<Integer, String> currentLvtNames = java.util.Collections.emptyMap();

    /** Check if a value is category 2 (long or double, occupies two JVM stack slots). */
    private static boolean isCategory2(Value v) {
        return v.type() != null && (v.type().kind() == TypeKind.LONG
                || v.type().kind() == TypeKind.DOUBLE);
    }

    // ─── Block ordering ───────────────────────────────────────────────

    /**
     * Build LinearIr from CFG by symbolic execution of each basic block.
     */
    public LinearIr build(ControlFlowGraph cfg, MethodModel method,
                          ConstantPoolEntry[] constantPool) {
        List<IrInstruction> allInstructions = new ArrayList<>();
        List<Variable> variables = new ArrayList<>();
        Map<BasicBlock, FrameState> blockOutputs = new HashMap<>();

        List<BasicBlock> blocks = orderBlocks(cfg);

        int maxLocals = method.maxLocals();
        if (maxLocals <= 0) {
            maxLocals = method.isStatic() ? 0 : 1;
        }

        for (BasicBlock block : blocks) {
            FrameState entry = mergePredecessorStates(block, blockOutputs, cfg, allInstructions, variables);
            if (entry == null) {
                entry = FrameState.withLocals(maxLocals);
            }
            FrameState exit = simulateBlock(block, entry, allInstructions, variables, cfg, method, constantPool,
                    method.localVarNames());
            blockOutputs.put(block, exit);
        }

        return new LinearIr(method, cfg, allInstructions, variables);
    }

    // ─── Predecessor merge ────────────────────────────────────────────

    private List<BasicBlock> orderBlocks(ControlFlowGraph cfg) {
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        Deque<BasicBlock> stack = new ArrayDeque<>();
        stack.push(cfg.entryBlock());
        while (!stack.isEmpty()) {
            BasicBlock b = stack.pop();
            if (!visited.add(b)) {
                continue;
            }
            if (b != cfg.entryBlock() && b != cfg.exitBlock()) {
                result.add(b);
            }
            for (BasicBlock succ : cfg.successorsOf(b)) {
                if (!visited.contains(succ)) {
                    stack.push(succ);
                }
            }
        }
        return result;
    }

    // ─── Main block simulation ────────────────────────────────────────

    /**
     * Merge states from all predecessors of a block.
     *
     * For variable slots: picks the latest variable version across all
     * predecessor states so that stores from any path are visible.
     * For the operand stack: JVM verification guarantees it's empty at
     * merge points (exception handlers are the exception — they have
     * exactly one element, the thrown exception).
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

        // Collect all predecessor states
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

        // Determine max locals size across all predecessors
        int maxLocals = 0;
        for (FrameState s : predStates) {
            maxLocals = Math.max(maxLocals, s.locals().length);
        }

        // Merge locals: for each slot, pick the version with the highest version
        // number from all predecessors — this ensures stores from any path
        // contribute their latest variable version.
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

        // Merge stack: detect if any predecessor is an exception edge.
        // Exception handlers have stack=[thrown_exception]; normal merges
        // have an empty stack (per JVM verification).
        boolean hasExceptionEdge = false;
        for (BasicBlock pred : preds) {
            for (var edge : cfg.incomingOf(block)) {
                if (edge.source().equals(pred)
                        && edge.kind() == com.bingbaihanji.bdec.cfg.EdgeKind.EXCEPTION) {
                    hasExceptionEdge = true;
                    break;
                }
            }
            if (hasExceptionEdge) break;
        }

        Deque<Value> mergedStack;
        if (hasExceptionEdge && !predStates.get(0).stack().isEmpty()) {
            // Exception handler: keep the exception reference on stack
            mergedStack = new ArrayDeque<>(predStates.get(0).stack());
        } else {
            // Normal merge point: stack must be empty per JVM verification
            mergedStack = new ArrayDeque<>();
        }

        return new FrameState(mergedStack, mergedLocals);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Handler methods — one per opcode category
    // ═══════════════════════════════════════════════════════════════════

    // ── Stack manipulation ───────────────────────────────────────

    /**
     * Symbolically execute one basic block. Each opcode dispatches to a
     * category-specific handler that preserves bytecode metadata for downstream
     * operator and name resolution.
     */
    private FrameState simulateBlock(BasicBlock block, FrameState entry,
                                     List<IrInstruction> instructions,
                                     List<Variable> variables,
                                     ControlFlowGraph cfg, MethodModel method,
                                     ConstantPoolEntry[] cp,
                                     java.util.Map<Integer, String> lvtNames) {
        this.currentLvtNames = lvtNames;
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

            // ── Dispatch to category handlers ─────────────────────────
            switch (op) {
                // Stack manipulation
                case NOP -> {
                }
                case POP, POP2 -> handlePop(op, stack);
                case DUP, DUP_X1, DUP_X2, DUP2, SWAP -> handleDup(op, stack);

                // Constants
                case ACONST_NULL, ICONST_M1, ICONST_0, ICONST_1, ICONST_2,
                     ICONST_3, ICONST_4, ICONST_5, LCONST_0, LCONST_1,
                     FCONST_0, FCONST_1, FCONST_2, DCONST_0, DCONST_1,
                     BIPUSH, SIPUSH -> handleConstant(op, insn, stack);
                case LDC, LDC_W, LDC2_W -> handleLdc(insn, stack, cp);

                // Loads
                case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3,
                     LLOAD, LLOAD_0, LLOAD_1, LLOAD_2, LLOAD_3,
                     FLOAD, FLOAD_0, FLOAD_1, FLOAD_2, FLOAD_3,
                     DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3,
                     ALOAD, ALOAD_0, ALOAD_1, ALOAD_2, ALOAD_3 ->
                        handleLoad(op, insn, stack, locals, variables, instructions, offset, blockId);

                // Stores
                case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3,
                     LSTORE, LSTORE_0, LSTORE_1, LSTORE_2, LSTORE_3,
                     FSTORE, FSTORE_0, FSTORE_1, FSTORE_2, FSTORE_3,
                     DSTORE, DSTORE_0, DSTORE_1, DSTORE_2, DSTORE_3,
                     ASTORE, ASTORE_0, ASTORE_1, ASTORE_2, ASTORE_3 ->
                        handleStore(op, insn, stack, locals, variables, instructions, offset, blockId);

                // IINC
                case IINC -> handleIinc(insn, variables, instructions, offset, blockId, locals);

                // Arithmetic (int)
                case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR ->
                        handleArithmetic(op, stack, instructions, JavaType.INT, offset, blockId);
                case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR ->
                        handleArithmetic(op, stack, instructions, JavaType.LONG, offset, blockId);
                case FADD, FSUB, FMUL, FDIV, FREM ->
                        handleArithmetic(op, stack, instructions, JavaType.FLOAT, offset, blockId);
                case DADD, DSUB, DMUL, DDIV, DREM ->
                        handleArithmetic(op, stack, instructions, JavaType.DOUBLE, offset, blockId);
                case INEG, LNEG, FNEG, DNEG -> handleNegate(op, stack, instructions, offset, blockId);

                // Comparisons — pass op so the bytecode can be stored for operator inference
                case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> handleComparison(op, stack, instructions, offset, blockId);

                // Returns
                case RETURN -> instructions.add(IrInstruction.returnInsn(nextId(), null, offset, blockId));
                case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN ->
                        instructions.add(IrInstruction.returnInsn(nextId(), stack.pop(), offset, blockId));

                // Fields — pass insn+cp for name resolution
                case GETSTATIC, GETFIELD -> handleFieldLoad(op, insn, stack, instructions, cp, offset, blockId);
                case PUTSTATIC, PUTFIELD -> handleFieldStore(op, insn, stack, instructions, cp, offset, blockId);

                // Invoke
                case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE ->
                        handleInvoke(op, insn, stack, instructions, cp, offset, blockId);
                case INVOKEDYNAMIC -> handleInvokeDynamic(insn, stack, instructions, cp, offset, blockId);

                // Object / Array
                case NEW -> handleNew(insn, stack, instructions, cp, offset, blockId);
                case NEWARRAY, ANEWARRAY -> handleNewArray(op, insn, stack, instructions, cp, offset, blockId);
                case ARRAYLENGTH -> handleArrayLength(stack, instructions, offset, blockId);

                // Type
                case CHECKCAST -> handleCheckCast(op, insn, stack, instructions, cp, offset, blockId);
                case INSTANCEOF -> handleInstanceOf(op, insn, stack, instructions, cp, offset, blockId);

                // Conversions — pass op.code() for cast type inference
                case I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D,
                     D2I, D2L, D2F, I2B, I2C, I2S -> handleConversion(op, stack, instructions, offset, blockId);

                // Monitor — emit IR instructions so SynchronizedRecognizer can detect them
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

                // Branches — pass op for comparison operator inference
                case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                     IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE,
                     IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE ->
                        handleCondition(op, stack, instructions, offset, blockId);
                case IFNULL, IFNONNULL -> handleNullCheck(op, stack, instructions, offset, blockId);
                case GOTO, GOTO_W -> {
                } // CFG handles these
                case MULTIANEWARRAY -> handleMultiNewArray(insn, stack, instructions, offset, blockId);
                case ATHROW -> instructions.add(new IrInstruction(nextId(), IrOpcode.THROW,
                        JavaType.VOID, List.of(stack.pop()), offset, blockId, op.code(), null));

                // Switch — pop key, CFG handles targets
                case TABLESWITCH, LOOKUPSWITCH -> {
                    if (!stack.isEmpty()) {
                        Value key = stack.pop();
                        instructions.add(new IrInstruction(nextId(), IrOpcode.SWITCH,
                                JavaType.VOID, List.of(key), offset, blockId, op.code(), null));
                    }
                }

                default -> {
                } // skip unknown opcodes
            }
        }

        return new FrameState(stack, locals);
    }

    private void handlePop(Opcode op, Deque<Value> stack) {
        if (stack.isEmpty()) {
            return;
        }
        stack.pop();
        if (op == Opcode.POP2 && !stack.isEmpty()) {
            stack.pop();
        }
    }

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
                    // Top value is long/double (category 2) — duplicate just it
                    stack.push(v1);
                    stack.push(v1);
                } else if (!stack.isEmpty()) {
                    // Top two values are both category 1 — duplicate both
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

    // ── Constants ─────────────────────────────────────────────────

    private void handleConstant(Opcode op, Instruction insn, Deque<Value> stack) {
        switch (op) {
            case ACONST_NULL -> stack.push(ConstantValue.NULL);
            case ICONST_M1 -> stack.push(new ConstantValue(-1, JavaType.INT));
            case ICONST_0 -> stack.push(new ConstantValue(0, JavaType.INT));
            case ICONST_1 -> stack.push(new ConstantValue(1, JavaType.INT));
            case ICONST_2 -> stack.push(new ConstantValue(2, JavaType.INT));
            case ICONST_3 -> stack.push(new ConstantValue(3, JavaType.INT));
            case ICONST_4 -> stack.push(new ConstantValue(4, JavaType.INT));
            case ICONST_5 -> stack.push(new ConstantValue(5, JavaType.INT));
            case LCONST_0 -> stack.push(new ConstantValue(0L, JavaType.LONG));
            case LCONST_1 -> stack.push(new ConstantValue(1L, JavaType.LONG));
            case FCONST_0 -> stack.push(new ConstantValue(0.0f, JavaType.FLOAT));
            case FCONST_1 -> stack.push(new ConstantValue(1.0f, JavaType.FLOAT));
            case FCONST_2 -> stack.push(new ConstantValue(2.0f, JavaType.FLOAT));
            case DCONST_0 -> stack.push(new ConstantValue(0.0, JavaType.DOUBLE));
            case DCONST_1 -> stack.push(new ConstantValue(1.0, JavaType.DOUBLE));
            case BIPUSH, SIPUSH -> {
                int val = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
                stack.push(new ConstantValue(val, JavaType.INT));
            }
        }
    }

    private void handleLdc(Instruction insn, Deque<Value> stack, ConstantPoolEntry[] cp) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        ConstantValue cv = cpIdx > 0 && cpIdx < cp.length
                ? cpValue(cp[cpIdx], cp)
                : new ConstantValue("?", JavaType.classType("java/lang/Object"));
        stack.push(cv);
    }

    // ── Loads ────────────────────────────────────────────────────

    private void handleLoad(Opcode op, Instruction insn, Deque<Value> stack,
                            Value[] locals, List<Variable> variables,
                            List<IrInstruction> instructions, int offset, int blockId) {
        int idx = varIndex(insn, op);
        JavaType type = loadType(op);
        Value v = locals[idx];
        if (v == null) {
            v = lookupReadVar(variables, idx, type);
        }
        // Ensure the variable has its LVT name even if it came from a predecessor's
        // frame state (where createWriteVar may have missed it for non-zero versions)
        if (v instanceof Variable var && currentLvtNames.containsKey(idx)
                && (var.name() == null || var.name().startsWith("var"))) {
            var.setName(currentLvtNames.get(idx));
        }
        emitLoad(v, instructions, offset, blockId);
        stack.push(v);
    }

    private JavaType loadType(Opcode op) {
        return switch (op) {
            case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3 -> JavaType.INT;
            case LLOAD, LLOAD_0, LLOAD_1, LLOAD_2, LLOAD_3 -> JavaType.LONG;
            case FLOAD, FLOAD_0, FLOAD_1, FLOAD_2, FLOAD_3 -> JavaType.FLOAT;
            case DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3 -> JavaType.DOUBLE;
            default -> JavaType.classType("java/lang/Object");
        };
    }

    // ── Stores ───────────────────────────────────────────────────

    private void handleStore(Opcode op, Instruction insn, Deque<Value> stack,
                             Value[] locals, List<Variable> variables,
                             List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        int idx = varIndex(insn, op);
        Value val = stack.pop();
        // Create a NEW version for each store — prevents "this" slot confusion
        Variable var = createWriteVar(variables, idx, val.type());
        // Store the NEW Variable in locals so subsequent LOADs find a Variable,
        // NOT the raw InstructionRef. This prevents expression expansion:
        // "n = cap - 1 | cap - 1 >>> 1" → "n = n | n >>> 1"
        locals[idx] = var;
        instructions.add(IrInstruction.store(nextId(), var, val, offset, blockId));
    }

    // ── IINC ─────────────────────────────────────────────────────

    private void handleIinc(Instruction insn, List<Variable> variables,
                            List<IrInstruction> instructions, int offset, int blockId,
                            Value[] locals) {
        int idx = varIndex(insn, Opcode.IINC);
        int incr = insn.rawOperands().size() > 1 ? insn.rawOperands().get(1) : 0;
        // IINC reads current value and writes new value — create a new version
        Variable readVar = lookupReadVar(variables, idx, JavaType.INT);
        Variable writeVar = createWriteVar(variables, idx, JavaType.INT);
        // Update locals so subsequent LOADs in the same block see the new version
        if (idx < locals.length) {
            locals[idx] = writeVar;
        }
        instructions.add(new IrInstruction(nextId(), IrOpcode.INC, JavaType.INT,
                List.of(readVar, writeVar, new ConstantValue(incr, JavaType.INT)),
                offset, blockId));
    }

    // ── Arithmetic ───────────────────────────────────────────────

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

    // ── Comparisons ──────────────────────────────────────────────

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

    // ── Fields ───────────────────────────────────────────────────

    private void handleFieldLoad(Opcode op, Instruction insn, Deque<Value> stack,
                                 List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                 int offset, int blockId) {
        Value obj = (op == Opcode.GETFIELD && !stack.isEmpty()) ? stack.pop() : null;
        String fieldName = resolveFieldName(insn, cp);
        IrInstruction fi = IrInstruction.fieldLoad(nextId(), obj,
                JavaType.classType("java/lang/Object"), offset, blockId, fieldName);
        instructions.add(fi);
        fi.setResultValue(new InstructionRef(fi, fi.resultType()));
        stack.push(new InstructionRef(fi, fi.resultType()));
    }

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

    /** Resolve a field name from the constant pool via a field-ref instruction. */
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
            // fall through to null
        }
        return null;
    }

    /** Resolve a method name from the constant pool via a method-ref instruction. */
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
            // fall through to null
        }
        return null;
    }

    // ── Invoke ───────────────────────────────────────────────────

    private void handleInvoke(Opcode op, Instruction insn, Deque<Value> stack,
                              List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                              int offset, int blockId) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        int argCount = 0;
        JavaType returnType = JavaType.classType("java/lang/Object");
        String methodName = null;
        String declaringClass = null; // for constructor delegation target
        com.bingbaihanji.bdec.type.JavaType[] paramTypes = null; // for boolean folding

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
                if (natIdx > 0 && natIdx < cp.length
                        && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                    String desc = ConstantPoolParser.utf8(cp, nat.descriptorIndex());
                    methodName = ConstantPoolParser.utf8(cp, nat.nameIndex());
                    paramTypes = com.bingbaihanji.bdec.type.TypeResolver.parseMethodParameterTypes(desc);
                    argCount = paramTypes.length;
                    returnType = com.bingbaihanji.bdec.type.TypeResolver.parseMethodReturnType(desc);
                }
            } catch (Exception ignored) {
                // keep defaults
            }
        }

        // Pop args in reverse order
        List<Value> args = new ArrayList<>();
        for (int a = 0; a < argCount && !stack.isEmpty(); a++) {
            args.addFirst(stack.pop());
        }
        // Capture receiver (for non-static calls) — preserves target for method calls
        Value receiver = null;
        if (op != Opcode.INVOKESTATIC && !stack.isEmpty()) {
            receiver = stack.pop();
        }

        // Fold boolean constants: 0→false, 1→true when parameter is boolean
        if (paramTypes != null) {
            for (int p = 0; p < paramTypes.length && p < args.size(); p++) {
                if (paramTypes[p].kind() == TypeKind.BOOLEAN
                        && args.get(p) instanceof ConstantValue cv
                        && cv.value() instanceof Integer i) {
                    args.set(p, new ConstantValue(i != 0, JavaType.BOOLEAN));
                }
            }
        }

        IrInstruction inv = IrInstruction.invoke(nextId(), receiver, args, returnType,
                offset, blockId, methodName);
        instructions.add(inv);

        // Tag static calls with declaring class (for Arrays.fill() vs fill())
        if (op == Opcode.INVOKESTATIC && declaringClass != null) {
            inv.addAnnotation(com.bingbaihanji.bdec.semantic.SemanticAnnotation.of(
                    com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS,
                    com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS,
                    declaringClass));
        }

        // Tag constructor delegation calls with target class info
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

    // ── InvokeDynamic ────────────────────────────────────────────

    private void handleInvokeDynamic(Instruction insn, Deque<Value> stack,
                                     List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                     int offset, int blockId) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        int argCount = 0;
        JavaType returnType = JavaType.classType("java/lang/Object");
        String methodName = "invokeDynamic";

        if (cpIdx > 0 && cpIdx < cp.length) {
            try {
                ConstantPoolEntry entry = cp[cpIdx];
                if (entry instanceof ConstantPoolEntry.CpInvokeDynamic indy) {
                    int natIdx = indy.nameAndTypeIndex();
                    if (natIdx > 0 && natIdx < cp.length
                            && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                        String desc = ConstantPoolParser.utf8(cp, nat.descriptorIndex());
                        methodName = ConstantPoolParser.utf8(cp, nat.nameIndex());
                        var params = com.bingbaihanji.bdec.type.TypeResolver.parseMethodParameterTypes(desc);
                        argCount = params.length;
                        returnType = com.bingbaihanji.bdec.type.TypeResolver.parseMethodReturnType(desc);
                    }
                }
            } catch (Exception ignored) {
                // keep defaults
            }
        }

        // Pop arguments
        List<Value> args = new ArrayList<>();
        for (int a = 0; a < argCount && !stack.isEmpty(); a++) {
            args.addFirst(stack.pop());
        }

        IrInstruction inv = IrInstruction.invoke(nextId(), null, args, returnType,
                offset, blockId, methodName);
        instructions.add(inv);
        if (returnType.kind() != TypeKind.VOID) {
            inv.setResultValue(new InstructionRef(inv, returnType));
            stack.push(new InstructionRef(inv, returnType));
        }
    }

    // ── Object / Array ───────────────────────────────────────────

    private void handleNew(Instruction insn, Deque<Value> stack,
                           List<IrInstruction> instructions,
                           ConstantPoolEntry[] cp, int offset, int blockId) {
        // Resolve the class type from the constant pool
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

    private void handleNewArray(Opcode op, Instruction insn, Deque<Value> stack,
                                List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                int offset, int blockId) {
        Value size = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                JavaType.classType("java/lang/Object"), List.of(size), offset, blockId);
        instructions.add(na);
        na.setResultValue(new InstructionRef(na, na.resultType()));
        stack.push(new InstructionRef(na, na.resultType()));
    }

    private void handleMultiNewArray(Instruction insn, Deque<Value> stack,
                                     List<IrInstruction> instructions,
                                     int offset, int blockId) {
        // MULTIANEWARRAY: operands = [cp_index, dimensions]
        int dims = 1;
        if (insn.rawOperands().size() > 1) {
            dims = insn.rawOperands().get(1);
        }
        // Pop dimension sizes from stack
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

    // ── Type / Cast ──────────────────────────────────────────────

    private void handleCheckCast(Opcode op, Instruction insn, Deque<Value> stack,
                                 List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                 int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        // Resolve target type from constant pool
        JavaType targetType = resolveClassType(insn, cp);
        IrInstruction c = IrInstruction.cast(nextId(), v, targetType, offset, blockId, op.code());
        instructions.add(c);
        c.setResultValue(new InstructionRef(c, c.resultType()));
        stack.push(new InstructionRef(c, c.resultType()));
    }

    private void handleInstanceOf(Opcode op, Instruction insn, Deque<Value> stack,
                                  List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                  int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value obj = stack.pop(); // preserve the object as an operand
        JavaType targetType = resolveClassType(insn, cp);
        IrInstruction io = new IrInstruction(nextId(), IrOpcode.INSTANCE_OF,
                JavaType.INT, List.of(obj), offset, blockId, op.code(), null);
        io.setResultValue(new InstructionRef(io, JavaType.INT));
        instructions.add(io);
        stack.push(new InstructionRef(io, JavaType.INT));
    }

    /** Resolve a class type from a CP-referencing instruction (checkcast, instanceof, anewarray). */
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

    // ── Branches ─────────────────────────────────────────────────

    private void handleCondition(Opcode op, Deque<Value> stack,
                                 List<IrInstruction> instructions, int offset, int blockId) {
        // Distinguish zero-comparisons (IFEQ..IFLE: one operand vs 0)
        // from int-comparisons (IF_ICMPxx: two operands).
        // For IFxx, the stack has ONE value and we compare it against 0.
        // Make the stack value the LEFT operand so "capacity > 0" reads correctly.
        boolean isIfCmp = switch (op) {
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                 IF_ACMPEQ, IF_ACMPNE -> true;
            default -> false;
        };
        if (isIfCmp) {
            // Two-operand pop order: right first, then left (standard JVM semantics)
            Value right = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            Value left = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                    JavaType.INT, List.of(left, right), offset, blockId, op.code(), null));
        } else {
            // IFxx — one operand: the stack value is the value being compared against 0
            // Put value as LEFT, 0 as RIGHT so e.g. IFGT produces "capacity > 0"
            Value val = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                    JavaType.INT, List.of(val, new ConstantValue(0, JavaType.INT)),
                    offset, blockId, op.code(), null));
        }
    }

    private void handleNullCheck(Opcode op, Deque<Value> stack, List<IrInstruction> instructions,
                                 int offset, int blockId) {
        Value ref = !stack.isEmpty() ? stack.pop() : ConstantValue.NULL;
        instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                JavaType.INT, List.of(ref, ConstantValue.NULL), offset, blockId, op.code(), null));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ═══════════════════════════════════════════════════════════════════

    private int nextId() {return nextInsnId++;}

    private int varIndex(Instruction insn, Opcode op) {
        if (op.implicitVarIndex() >= 0) {
            return op.implicitVarIndex();
        }
        if (!insn.rawOperands().isEmpty()) {
            return insn.rawOperands().get(0);
        }
        return 0;
    }

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

    /** Create a NEW variable version for a STORE (slot is being written to).
     *  This prevents slot 0 ('this') from being confused with a temp stored at slot 0. */
    private Variable createWriteVar(List<Variable> variables, int slot, JavaType type) {
        int maxVersion = 0;
        for (Variable v : variables) {
            if (v.slot() == slot) {
                maxVersion = Math.max(maxVersion, v.version());
            }
        }
        Variable v = new Variable(slot, maxVersion + 1, type, false, slot);
        // Carry forward LVT name so new versions retain original parameter names
        if (currentLvtNames.containsKey(slot)) {
            v.setName(currentLvtNames.get(slot));
        }
        variables.add(v);
        return v;
    }

    /** Find the latest version of a variable at the given slot (for LOAD). */
    private Variable lookupReadVar(List<Variable> variables, int slot, JavaType type) {
        Variable latest = null;
        for (Variable v : variables) {
            if (v.slot() == slot && (latest == null || v.version() > latest.version())) {
                latest = v;
            }
        }
        if (latest != null) {
            return latest;
        }
        // First access: create version 0, apply LVT name if available
        Variable v = new Variable(slot, 0, type, false, slot);
        if (currentLvtNames.containsKey(slot)) {
            v.setName(currentLvtNames.get(slot));
        }
        variables.add(v);
        return v;
    }

    @SuppressWarnings("unused")
    private Variable asVar(Value v, List<Variable> variables, int slot) {
        if (v instanceof Variable var) {
            return var;
        }
        return getOrCreateVar(variables, slot, v.type(), false);
    }

    private void emitLoad(Value v, List<IrInstruction> instructions, int offset, int blockId) {
        if (v instanceof Variable var) {
            IrInstruction load = IrInstruction.load(nextId(), var, offset, blockId);
            instructions.add(load);
            load.setResultValue(new InstructionRef(load, v.type()));
        }
    }

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
                    ConstantPoolParser.className(pool, c.nameIndex()),
                    JavaType.classType("java/lang/Class"));
            default -> new ConstantValue("<cp:" + entry.tag() + ">", JavaType.classType("java/lang/Object"));
        };
    }

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
