package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.opcode.Opcode;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.type.JavaType;

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
 * and local variable array. Stack manipulation instructions (dup, swap, pop)
 * operate on the symbol stack directly. Computational instructions (add, invoke,
 * field access) pop operands, create an IrInstruction, and push the result.
 */
public final class IrBuilder {

    private int nextInsnId = 0;

    private int nextVarId = 0;

    /**
     * Build LinearIr from CFG by symbolic execution of each basic block.
     */
    public LinearIr build(ControlFlowGraph cfg, MethodModel method) {
        List<IrInstruction> allInstructions = new ArrayList<>();
        List<Variable> variables = new ArrayList<>();
        Map<BasicBlock, FrameState> blockOutputs = new HashMap<>();

        // Process blocks in order — dominator-preferred order for cleaner PHI placement
        List<BasicBlock> blocks = orderBlocks(cfg);

        // Initialize locals from method parameters
        int maxLocals = method.maxLocals();
        if (maxLocals <= 0) {
            maxLocals = method.isStatic() ? 0 : 1; // at least 'this'
        }

        for (BasicBlock block : blocks) {
            // Merge predecessor states for entry
            FrameState entry = mergePredecessorStates(block, blockOutputs, cfg, allInstructions, variables);
            if (entry == null) {
                entry = FrameState.withLocals(maxLocals);
            }

            // Symbolically execute the block
            FrameState exit = simulateBlock(block, entry, allInstructions, variables, cfg, method);
            blockOutputs.put(block, exit);
        }

        return new LinearIr(method, cfg, allInstructions, variables);
    }

    /**
     * Order blocks for processing. Entry block first, then dominator-tree order.
     */
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
        return result; // approximate; dominator tree order would be better
    }

    /**
     * Merge states from multiple predecessors. Returns null if no predecessors.
     */
    private FrameState mergePredecessorStates(BasicBlock block,
                                              Map<BasicBlock, FrameState> outputs, ControlFlowGraph cfg,
                                              List<IrInstruction> instructions, List<Variable> variables) {
        List<BasicBlock> preds = cfg.predecessorsOf(block);
        if (preds.isEmpty()) {
            return null;
        }
        if (preds.size() == 1) {
            return outputs.get(preds.get(0));
        }

        // Multiple predecessors — merge
        FrameState first = outputs.get(preds.get(0));
        if (first == null) {
            return null;
        }
        return first.copy(); // simple copy for now; PHI insertion in SSA phase
    }

    /**
     * Symbolically execute one basic block.
     */
    private FrameState simulateBlock(BasicBlock block, FrameState entry,
                                     List<IrInstruction> instructions, List<Variable> variables,
                                     ControlFlowGraph cfg, MethodModel method) {
        Deque<Value> stack = new ArrayDeque<>(entry.stack());
        Value[] locals = entry.locals().clone();

        for (Instruction insn : block.instructions()) {
            int offset = insn.offset();
            Opcode op;
            try {
                op = Opcode.byCode(insn.opcode());
            } catch (IllegalArgumentException e) {
                continue;
            }

            switch (op) {
                // === Stack manipulation ===
                case NOP -> {
                }
                case POP -> stack.pop();
                case POP2 -> {
                    stack.pop();
                    stack.pop();
                }
                case DUP -> {
                    Value v = stack.pop();
                    stack.push(v);
                    stack.push(v);
                }
                case DUP_X1 -> {
                    Value v1 = stack.pop(), v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                case DUP_X2 -> {
                    Value v1 = stack.pop(), v2 = stack.pop(), v3 = stack.pop();
                    stack.push(v1);
                    stack.push(v3);
                    stack.push(v2);
                    stack.push(v1);
                }
                case DUP2 -> {
                    Value v1 = stack.pop(), v2 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                case SWAP -> {
                    Value v1 = stack.pop(), v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                }

                // === Constants ===
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
                case LDC -> stack.push(new ConstantValue("<ldc>", JavaType.classType("java/lang/Object")));

                // === Loads ===
                case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3 -> {
                    int idx = varIndex(insn, op);
                    Value v = locals[idx];
                    if (v == null) {
                        v = getOrCreateVar(variables, idx, JavaType.INT, false);
                    }
                    IrInstruction load = IrInstruction.load(nextId(), (Variable) v, offset, block.id());
                    instructions.add(load);
                    load.setResultValue(new InstructionRef(load, v.type()));
                    stack.push(new InstructionRef(load, v.type()));
                }
                case LLOAD, LLOAD_0, LLOAD_1, LLOAD_2, LLOAD_3 -> {
                    int idx = varIndex(insn, op);
                    Value v = locals[idx];
                    if (v == null) {
                        v = getOrCreateVar(variables, idx, JavaType.LONG, false);
                    }
                    stack.push(v);
                }
                case FLOAD, FLOAD_0, FLOAD_1, FLOAD_2, FLOAD_3 -> {
                    int idx = varIndex(insn, op);
                    Value v = locals[idx];
                    if (v == null) {
                        v = getOrCreateVar(variables, idx, JavaType.FLOAT, false);
                    }
                    stack.push(v);
                }
                case DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3 -> {
                    int idx = varIndex(insn, op);
                    Value v = locals[idx];
                    if (v == null) {
                        v = getOrCreateVar(variables, idx, JavaType.DOUBLE, false);
                    }
                    stack.push(v);
                }
                case ALOAD, ALOAD_0, ALOAD_1, ALOAD_2, ALOAD_3 -> {
                    int idx = varIndex(insn, op);
                    Value v = locals[idx];
                    if (v == null) {
                        v = getOrCreateVar(variables, idx, JavaType.classType("java/lang/Object"), false);
                    }
                    IrInstruction load = IrInstruction.load(nextId(), (Variable) v, offset, block.id());
                    instructions.add(load);
                    load.setResultValue(new InstructionRef(load, v.type()));
                    stack.push(new InstructionRef(load, v.type()));
                }

                // === Stores ===
                case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3,
                     LSTORE, LSTORE_0, LSTORE_1, LSTORE_2, LSTORE_3,
                     FSTORE, FSTORE_0, FSTORE_1, FSTORE_2, FSTORE_3,
                     DSTORE, DSTORE_0, DSTORE_1, DSTORE_2, DSTORE_3,
                     ASTORE, ASTORE_0, ASTORE_1, ASTORE_2, ASTORE_3 -> {
                    int idx = varIndex(insn, op);
                    Value val = stack.pop();
                    locals[idx] = val;
                    Variable var = getOrCreateVar(variables, idx, val.type(), false);
                    IrInstruction store = IrInstruction.store(nextId(), var, val, offset, block.id());
                    instructions.add(store);
                }

                // === Arithmetic ===
                case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR -> {
                    Value right = stack.pop(), left = stack.pop();
                    IrInstruction bin = IrInstruction.binary(nextId(), IrOpcode.BINARY, left, right, JavaType.INT, offset, block.id());
                    instructions.add(bin);
                    bin.setResultValue(new InstructionRef(bin, JavaType.INT));
                    stack.push(new InstructionRef(bin, JavaType.INT));
                }
                case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR -> {
                    Value right = stack.pop(), left = stack.pop();
                    IrInstruction bin = IrInstruction.binary(nextId(), IrOpcode.BINARY, left, right, JavaType.LONG, offset, block.id());
                    instructions.add(bin);
                    bin.setResultValue(new InstructionRef(bin, JavaType.LONG));
                    stack.push(new InstructionRef(bin, JavaType.LONG));
                }
                case FADD, FSUB, FMUL, FDIV, FREM -> {
                    Value right = stack.pop(), left = stack.pop();
                    IrInstruction bin = IrInstruction.binary(nextId(), IrOpcode.BINARY, left, right, JavaType.FLOAT, offset, block.id());
                    instructions.add(bin);
                    bin.setResultValue(new InstructionRef(bin, JavaType.FLOAT));
                    stack.push(new InstructionRef(bin, JavaType.FLOAT));
                }
                case DADD, DSUB, DMUL, DDIV, DREM -> {
                    Value right = stack.pop(), left = stack.pop();
                    IrInstruction bin = IrInstruction.binary(nextId(), IrOpcode.BINARY, left, right, JavaType.DOUBLE, offset, block.id());
                    instructions.add(bin);
                    bin.setResultValue(new InstructionRef(bin, JavaType.DOUBLE));
                    stack.push(new InstructionRef(bin, JavaType.DOUBLE));
                }
                case INEG, LNEG, FNEG, DNEG -> {
                    Value v = stack.pop();
                    IrInstruction un = new IrInstruction(nextId(), IrOpcode.UNARY, v.type(), List.of(v), offset, block.id());
                    instructions.add(un);
                    un.setResultValue(new InstructionRef(un, v.type()));
                    stack.push(new InstructionRef(un, v.type()));
                }

                // === Comparisons ===
                case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> {
                    Value right = stack.pop(), left = stack.pop();
                    IrInstruction cmp = IrInstruction.binary(nextId(), IrOpcode.COMPARE, left, right, JavaType.INT, offset, block.id());
                    instructions.add(cmp);
                    cmp.setResultValue(new InstructionRef(cmp, JavaType.INT));
                    stack.push(new InstructionRef(cmp, JavaType.INT));
                }

                // === Returns ===
                case RETURN -> {
                    IrInstruction ret = IrInstruction.returnInsn(nextId(), null, offset, block.id());
                    instructions.add(ret);
                }
                case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN -> {
                    Value v = stack.pop();
                    IrInstruction ret = IrInstruction.returnInsn(nextId(), v, offset, block.id());
                    instructions.add(ret);
                }

                // === Field access ===
                case GETSTATIC, GETFIELD -> {
                    Value obj = (op == Opcode.GETFIELD) ? stack.pop() : null;
                    IrInstruction fi = IrInstruction.fieldLoad(nextId(), obj,
                            JavaType.classType("java/lang/Object"), offset, block.id());
                    instructions.add(fi);
                    fi.setResultValue(new InstructionRef(fi, fi.resultType()));
                    stack.push(new InstructionRef(fi, fi.resultType()));
                }
                case PUTSTATIC, PUTFIELD -> {
                    Value val = stack.pop();
                    Value obj = (op == Opcode.PUTFIELD) ? stack.pop() : null;
                    IrInstruction fs = IrInstruction.fieldStore(nextId(), obj, val, offset, block.id());
                    instructions.add(fs);
                }

                // === Invoke ===
                case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE -> {
                    // Pop args (unknown count — simplified for now)
                    int argCount = 0; // placeholder; real impl reads from CP
                    List<Value> args = new ArrayList<>();
                    Value target = null;
                    if (op != Opcode.INVOKESTATIC) {
                        // pop receiver
                    }
                    IrInstruction inv = IrInstruction.invoke(nextId(), target, args,
                            JavaType.classType("java/lang/Object"), offset, block.id());
                    instructions.add(inv);
                    if (!insn.mnemonic().contains("void")) {
                        inv.setResultValue(new InstructionRef(inv, inv.resultType()));
                        stack.push(new InstructionRef(inv, inv.resultType()));
                    }
                }

                // === Object/Array ===
                case NEW -> {
                    IrInstruction n = IrInstruction.newInsn(nextId(),
                            JavaType.classType("java/lang/Object"), offset, block.id());
                    instructions.add(n);
                    n.setResultValue(new InstructionRef(n, n.resultType()));
                    stack.push(new InstructionRef(n, n.resultType()));
                }
                case NEWARRAY, ANEWARRAY -> {
                    stack.pop(); // size
                    IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                            JavaType.classType("java/lang/Object"), List.of(), offset, block.id());
                    instructions.add(na);
                    na.setResultValue(new InstructionRef(na, na.resultType()));
                    stack.push(new InstructionRef(na, na.resultType()));
                }
                case ARRAYLENGTH -> {
                    Value arr = stack.pop();
                    IrInstruction al = new IrInstruction(nextId(), IrOpcode.ARRAY_LENGTH,
                            JavaType.INT, List.of(arr), offset, block.id());
                    instructions.add(al);
                    al.setResultValue(new InstructionRef(al, JavaType.INT));
                    stack.push(new InstructionRef(al, JavaType.INT));
                }

                // === Type ===
                case CHECKCAST -> {
                    Value v = stack.pop();
                    IrInstruction c = IrInstruction.cast(nextId(), v,
                            JavaType.classType("java/lang/Object"), offset, block.id());
                    instructions.add(c);
                    c.setResultValue(new InstructionRef(c, c.resultType()));
                    stack.push(new InstructionRef(c, c.resultType()));
                }
                case INSTANCEOF -> {
                    stack.pop(); // obj ref
                    IrInstruction io = new IrInstruction(nextId(), IrOpcode.INSTANCE_OF,
                            JavaType.INT, List.of(), offset, block.id());
                    instructions.add(io);
                    io.setResultValue(new InstructionRef(io, JavaType.INT));
                    stack.push(new InstructionRef(io, JavaType.INT));
                }

                // === Conversions ===
                case I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D, D2I, D2L, D2F, I2B, I2C, I2S -> {
                    Value v = stack.pop();
                    JavaType to = targetType(op);
                    IrInstruction conv = IrInstruction.cast(nextId(), v, to, offset, block.id());
                    instructions.add(conv);
                    conv.setResultValue(new InstructionRef(conv, to));
                    stack.push(new InstructionRef(conv, to));
                }

                // === Monitor ===
                case MONITORENTER, MONITOREXIT -> stack.pop(); // obj ref

                // === Branch (handled by CFG structure, not IR) ===
                case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IF_ICMPEQ, IF_ICMPNE,
                     IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE -> {
                    if (!stack.isEmpty()) {
                        stack.pop(); // condition value
                    }
                    if (!stack.isEmpty()) {
                        stack.pop(); // second operand for if_icmp variants
                    }
                    IrInstruction ci = new IrInstruction(nextId(), IrOpcode.CONDITION,
                            JavaType.INT, List.of(), offset, block.id());
                    instructions.add(ci);
                }
                case IFNULL, IFNONNULL -> {
                    if (!stack.isEmpty()) {
                        stack.pop(); // obj ref
                    }
                    IrInstruction ci = new IrInstruction(nextId(), IrOpcode.CONDITION,
                            JavaType.INT, List.of(), offset, block.id());
                    instructions.add(ci);
                }
                case GOTO -> {
                } // CFG handles this
                case ATHROW -> {
                    Value ex = stack.pop();
                    IrInstruction th = new IrInstruction(nextId(), IrOpcode.THROW,
                            JavaType.VOID, List.of(ex), offset, block.id());
                    instructions.add(th);
                }

                // === IINC ===
                case IINC -> {
                    int idx = varIndex(insn, op);
                    int incr = insn.rawOperands().size() > 1 ? insn.rawOperands().get(1) : 0;
                    Variable var = getOrCreateVar(variables, idx, JavaType.INT, false);
                    IrInstruction inc = new IrInstruction(nextId(), IrOpcode.INC,
                            JavaType.INT, List.of(var, new ConstantValue(incr, JavaType.INT)),
                            offset, block.id());
                    instructions.add(inc);
                }

                default -> {
                } // skip unknown/unhandled (Phase 1b adds more)
            }
        }

        return new FrameState(stack, locals);
    }

    // --- Helpers ---

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
