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
            FrameState exit = simulateBlock(block, entry, allInstructions, variables, cfg, method, constantPool);
            blockOutputs.put(block, exit);
        }

        return new LinearIr(method, cfg, allInstructions, variables);
    }

    // ─── Block ordering ───────────────────────────────────────────────

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

    // ─── Predecessor merge ────────────────────────────────────────────

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
        FrameState first = outputs.get(preds.get(0));
        return first != null ? first.copy() : null;
    }

    // ─── Main block simulation ────────────────────────────────────────

    /**
     * Symbolically execute one basic block. Each opcode dispatches to a
     * category-specific handler that preserves bytecode metadata for downstream
     * operator and name resolution.
     */
    private FrameState simulateBlock(BasicBlock block, FrameState entry,
                                     List<IrInstruction> instructions,
                                     List<Variable> variables,
                                     ControlFlowGraph cfg, MethodModel method,
                                     ConstantPoolEntry[] cp) {
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
                case LDC -> handleLdc(insn, stack, cp);

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
                case IINC -> handleIinc(insn, variables, instructions, offset, blockId);

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

                // Object / Array
                case NEW -> handleNew(stack, instructions, offset, blockId);
                case NEWARRAY, ANEWARRAY -> handleNewArray(stack, instructions, offset, blockId);
                case ARRAYLENGTH -> handleArrayLength(stack, instructions, offset, blockId);

                // Type
                case CHECKCAST -> handleCheckCast(op, stack, instructions, offset, blockId);
                case INSTANCEOF -> handleInstanceOf(op, stack, instructions, offset, blockId);

                // Conversions — pass op.code() for cast type inference
                case I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D,
                     D2I, D2L, D2F, I2B, I2C, I2S -> handleConversion(op, stack, instructions, offset, blockId);

                // Monitor
                case MONITORENTER, MONITOREXIT -> stack.pop();

                // Branches — pass op for comparison operator inference
                case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                     IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE,
                     IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE ->
                        handleCondition(op, stack, instructions, offset, blockId);
                case IFNULL, IFNONNULL -> handleNullCheck(op, stack, instructions, offset, blockId);
                case GOTO -> {
                } // CFG handles this
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

    // ═══════════════════════════════════════════════════════════════════
    //  Handler methods — one per opcode category
    // ═══════════════════════════════════════════════════════════════════

    // ── Stack manipulation ───────────────────────────────────────

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
                if (stack.size() < 2) {
                    return;
                }
                Value v1 = stack.pop(), v2 = stack.pop();
                stack.push(v2);
                stack.push(v1);
                stack.push(v2);
                stack.push(v1);
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
            v = getOrCreateVar(variables, idx, type, false);
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
        locals[idx] = val;
        Variable var = getOrCreateVar(variables, idx, val.type(), false);
        instructions.add(IrInstruction.store(nextId(), var, val, offset, blockId));
    }

    // ── IINC ─────────────────────────────────────────────────────

    private void handleIinc(Instruction insn, List<Variable> variables,
                            List<IrInstruction> instructions, int offset, int blockId) {
        int idx = varIndex(insn, Opcode.IINC);
        int incr = insn.rawOperands().size() > 1 ? insn.rawOperands().get(1) : 0;
        Variable var = getOrCreateVar(variables, idx, JavaType.INT, false);
        instructions.add(new IrInstruction(nextId(), IrOpcode.INC, JavaType.INT,
                List.of(var, new ConstantValue(incr, JavaType.INT)), offset, blockId));
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

        if (cpIdx > 0 && cpIdx < cp.length) {
            try {
                ConstantPoolEntry cpEntry = cp[cpIdx];
                int natIdx = switch (cpEntry) {
                    case ConstantPoolEntry.CpMethodRef ref -> ref.nameAndTypeIndex();
                    case ConstantPoolEntry.CpInterfaceMethodRef ref -> ref.nameAndTypeIndex();
                    default -> -1;
                };
                if (natIdx > 0 && natIdx < cp.length
                        && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                    String desc = ConstantPoolParser.utf8(cp, nat.descriptorIndex());
                    methodName = ConstantPoolParser.utf8(cp, nat.nameIndex());
                    var params = com.bingbaihanji.bdec.type.TypeResolver.parseMethodParameterTypes(desc);
                    argCount = params.length;
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
        if (op != Opcode.INVOKESTATIC && !stack.isEmpty()) {
            stack.pop(); // receiver
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

    private void handleNew(Deque<Value> stack, List<IrInstruction> instructions,
                           int offset, int blockId) {
        IrInstruction n = IrInstruction.newInsn(nextId(),
                JavaType.classType("java/lang/Object"), offset, blockId);
        instructions.add(n);
        n.setResultValue(new InstructionRef(n, n.resultType()));
        stack.push(new InstructionRef(n, n.resultType()));
    }

    private void handleNewArray(Deque<Value> stack, List<IrInstruction> instructions,
                                int offset, int blockId) {
        if (!stack.isEmpty()) {
            stack.pop(); // size
        }
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                JavaType.classType("java/lang/Object"), List.of(), offset, blockId);
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

    private void handleCheckCast(Opcode op, Deque<Value> stack, List<IrInstruction> instructions,
                                 int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        IrInstruction c = IrInstruction.cast(nextId(), v,
                JavaType.classType("java/lang/Object"), offset, blockId, op.code());
        instructions.add(c);
        c.setResultValue(new InstructionRef(c, c.resultType()));
        stack.push(new InstructionRef(c, c.resultType()));
    }

    private void handleInstanceOf(Opcode op, Deque<Value> stack, List<IrInstruction> instructions,
                                  int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        stack.pop(); // obj ref
        IrInstruction io = new IrInstruction(nextId(), IrOpcode.INSTANCE_OF,
                JavaType.INT, List.of(), offset, blockId, op.code(), null);
        instructions.add(io);
        io.setResultValue(new InstructionRef(io, JavaType.INT));
        stack.push(new InstructionRef(io, JavaType.INT));
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
        Value right = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
        Value left = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
        instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                JavaType.INT, List.of(left, right), offset, blockId, op.code(), null));
    }

    private void handleNullCheck(Opcode op, Deque<Value> stack, List<IrInstruction> instructions,
                                 int offset, int blockId) {
        Value ref = !stack.isEmpty() ? stack.pop() : ConstantValue.NULL;
        instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                JavaType.INT, List.of(ref), offset, blockId, op.code(), null));
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
