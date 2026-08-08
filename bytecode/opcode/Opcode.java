package com.bingbaihanji.bdec.bytecode.opcode;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM opcode metadata. Phase 1a: ~140 high-frequency opcodes.
 * Phase 1b adds remaining opcodes (tableswitch, lookupswitch, invokedynamic, wide, etc.).
 */
public enum Opcode {
    // === Constants (0-20) ===
    NOP(0, "nop", 0, 0, true, false, false, -1),
    ACONST_NULL(1, "aconst_null", 0, 1, true, false, false, -1),
    ICONST_M1(2, "iconst_m1", 0, 1, true, false, false, -1),
    ICONST_0(3, "iconst_0", 0, 1, true, false, false, -1),
    ICONST_1(4, "iconst_1", 0, 1, true, false, false, -1),
    ICONST_2(5, "iconst_2", 0, 1, true, false, false, -1),
    ICONST_3(6, "iconst_3", 0, 1, true, false, false, -1),
    ICONST_4(7, "iconst_4", 0, 1, true, false, false, -1),
    ICONST_5(8, "iconst_5", 0, 1, true, false, false, -1),
    LCONST_0(9, "lconst_0", 0, 2, true, false, false, -1),
    LCONST_1(10, "lconst_1", 0, 2, true, false, false, -1),
    FCONST_0(11, "fconst_0", 0, 1, true, false, false, -1),
    FCONST_1(12, "fconst_1", 0, 1, true, false, false, -1),
    FCONST_2(13, "fconst_2", 0, 1, true, false, false, -1),
    DCONST_0(14, "dconst_0", 0, 2, true, false, false, -1),
    DCONST_1(15, "dconst_1", 0, 2, true, false, false, -1),
    BIPUSH(16, "bipush", 1, 1, true, false, false, -1),
    SIPUSH(17, "sipush", 2, 1, true, false, false, -1),
    LDC(18, "ldc", 1, 1, true, false, false, -1),
    LDC_W(19, "ldc_w", 2, 1, true, false, false, -1),
    LDC2_W(20, "ldc2_w", 2, 2, true, false, false, -1),

    // === Loads (21-45) ===
    ILOAD(21, "iload", 1, 1, true, false, false, 0),
    LLOAD(22, "lload", 1, 2, true, false, false, 0),
    FLOAD(23, "fload", 1, 1, true, false, false, 0),
    DLOAD(24, "dload", 1, 2, true, false, false, 0),
    ALOAD(25, "aload", 1, 1, true, false, false, 0),
    ILOAD_0(26, "iload_0", 0, 1, true, false, false, 0),
    ILOAD_1(27, "iload_1", 0, 1, true, false, false, 1),
    ILOAD_2(28, "iload_2", 0, 1, true, false, false, 2),
    ILOAD_3(29, "iload_3", 0, 1, true, false, false, 3),
    LLOAD_0(30, "lload_0", 0, 2, true, false, false, 0),
    LLOAD_1(31, "lload_1", 0, 2, true, false, false, 1),
    LLOAD_2(32, "lload_2", 0, 2, true, false, false, 2),
    LLOAD_3(33, "lload_3", 0, 2, true, false, false, 3),
    FLOAD_0(34, "fload_0", 0, 1, true, false, false, 0),
    FLOAD_1(35, "fload_1", 0, 1, true, false, false, 1),
    FLOAD_2(36, "fload_2", 0, 1, true, false, false, 2),
    FLOAD_3(37, "fload_3", 0, 1, true, false, false, 3),
    DLOAD_0(38, "dload_0", 0, 2, true, false, false, 0),
    DLOAD_1(39, "dload_1", 0, 2, true, false, false, 1),
    DLOAD_2(40, "dload_2", 0, 2, true, false, false, 2),
    DLOAD_3(41, "dload_3", 0, 2, true, false, false, 3),
    ALOAD_0(42, "aload_0", 0, 1, true, false, false, 0),
    ALOAD_1(43, "aload_1", 0, 1, true, false, false, 1),
    ALOAD_2(44, "aload_2", 0, 1, true, false, false, 2),
    ALOAD_3(45, "aload_3", 0, 1, true, false, false, 3),

    // === Array loads (46-53) ===
    IALOAD(46, "iaload", 0, -1, true, false, false, -1),
    LALOAD(47, "laload", 0, 0, true, false, false, -1),
    FALOAD(48, "faload", 0, -1, true, false, false, -1),
    DALOAD(49, "daload", 0, 0, true, false, false, -1),
    AALOAD(50, "aaload", 0, -1, true, false, false, -1),
    BALOAD(51, "baload", 0, -1, true, false, false, -1),
    CALOAD(52, "caload", 0, -1, true, false, false, -1),
    SALOAD(53, "saload", 0, -1, true, false, false, -1),

    // === Stores (54-78) ===
    ISTORE(54, "istore", 1, -1, true, false, false, 0),
    LSTORE(55, "lstore", 1, -2, true, false, false, 0),
    FSTORE(56, "fstore", 1, -1, true, false, false, 0),
    DSTORE(57, "dstore", 1, -2, true, false, false, 0),
    ASTORE(58, "astore", 1, -1, true, false, false, 0),
    ISTORE_0(59, "istore_0", 0, -1, true, false, false, 0),
    ISTORE_1(60, "istore_1", 0, -1, true, false, false, 1),
    ISTORE_2(61, "istore_2", 0, -1, true, false, false, 2),
    ISTORE_3(62, "istore_3", 0, -1, true, false, false, 3),
    LSTORE_0(63, "lstore_0", 0, -2, true, false, false, 0),
    LSTORE_1(64, "lstore_1", 0, -2, true, false, false, 1),
    LSTORE_2(65, "lstore_2", 0, -2, true, false, false, 2),
    LSTORE_3(66, "lstore_3", 0, -2, true, false, false, 3),
    FSTORE_0(67, "fstore_0", 0, -1, true, false, false, 0),
    FSTORE_1(68, "fstore_1", 0, -1, true, false, false, 1),
    FSTORE_2(69, "fstore_2", 0, -1, true, false, false, 2),
    FSTORE_3(70, "fstore_3", 0, -1, true, false, false, 3),
    DSTORE_0(71, "dstore_0", 0, -2, true, false, false, 0),
    DSTORE_1(72, "dstore_1", 0, -2, true, false, false, 1),
    DSTORE_2(73, "dstore_2", 0, -2, true, false, false, 2),
    DSTORE_3(74, "dstore_3", 0, -2, true, false, false, 3),
    ASTORE_0(75, "astore_0", 0, -1, true, false, false, 0),
    ASTORE_1(76, "astore_1", 0, -1, true, false, false, 1),
    ASTORE_2(77, "astore_2", 0, -1, true, false, false, 2),
    ASTORE_3(78, "astore_3", 0, -1, true, false, false, 3),

    // === Array stores (79-86) ===
    IASTORE(79, "iastore", 0, -3, true, false, false, -1),
    LASTORE(80, "lastore", 0, -4, true, false, false, -1),
    FASTORE(81, "fastore", 0, -3, true, false, false, -1),
    DASTORE(82, "dastore", 0, -4, true, false, false, -1),
    AASTORE(83, "aastore", 0, -3, true, false, false, -1),
    BASTORE(84, "bastore", 0, -3, true, false, false, -1),
    CASTORE(85, "castore", 0, -3, true, false, false, -1),
    SASTORE(86, "sastore", 0, -3, true, false, false, -1),

    // === Stack manipulation (87-95) ===
    POP(87, "pop", 0, -1, true, false, false, -1),
    POP2(88, "pop2", 0, -2, true, false, false, -1),
    DUP(89, "dup", 0, 1, true, false, false, -1),
    DUP_X1(90, "dup_x1", 0, 1, true, false, false, -1),
    DUP_X2(91, "dup_x2", 0, 1, true, false, false, -1),
    DUP2(92, "dup2", 0, 2, true, false, false, -1),
    DUP2_X1(93, "dup2_x1", 0, 2, true, false, false, -1),
    DUP2_X2(94, "dup2_x2", 0, 2, true, false, false, -1),
    SWAP(95, "swap", 0, 0, true, false, false, -1),

    // === Arithmetic (96-119) ===
    IADD(96, "iadd", 0, -1, true, false, false, -1),
    LADD(97, "ladd", 0, -2, true, false, false, -1),
    FADD(98, "fadd", 0, -1, true, false, false, -1),
    DADD(99, "dadd", 0, -2, true, false, false, -1),
    ISUB(100, "isub", 0, -1, true, false, false, -1),
    LSUB(101, "lsub", 0, -2, true, false, false, -1),
    FSUB(102, "fsub", 0, -1, true, false, false, -1),
    DSUB(103, "dsub", 0, -2, true, false, false, -1),
    IMUL(104, "imul", 0, -1, true, false, false, -1),
    LMUL(105, "lmul", 0, -2, true, false, false, -1),
    FMUL(106, "fmul", 0, -1, true, false, false, -1),
    DMUL(107, "dmul", 0, -2, true, false, false, -1),
    IDIV(108, "idiv", 0, -1, true, false, false, -1),
    LDIV(109, "ldiv", 0, -2, true, false, false, -1),
    FDIV(110, "fdiv", 0, -1, true, false, false, -1),
    DDIV(111, "ddiv", 0, -2, true, false, false, -1),
    IREM(112, "irem", 0, -1, true, false, false, -1),
    LREM(113, "lrem", 0, -2, true, false, false, -1),
    FREM(114, "frem", 0, -1, true, false, false, -1),
    DREM(115, "drem", 0, -2, true, false, false, -1),
    INEG(116, "ineg", 0, 0, true, false, false, -1),
    LNEG(117, "lneg", 0, 0, true, false, false, -1),
    FNEG(118, "fneg", 0, 0, true, false, false, -1),
    DNEG(119, "dneg", 0, 0, true, false, false, -1),

    // === Bitwise/Shift (120-132) ===
    ISHL(120, "ishl", 0, -1, true, false, false, -1),
    LSHL(121, "lshl", 0, -1, true, false, false, -1),
    ISHR(122, "ishr", 0, -1, true, false, false, -1),
    LSHR(123, "lshr", 0, -1, true, false, false, -1),
    IUSHR(124, "iushr", 0, -1, true, false, false, -1),
    LUSHR(125, "lushr", 0, -1, true, false, false, -1),
    IAND(126, "iand", 0, -1, true, false, false, -1),
    LAND(127, "land", 0, -2, true, false, false, -1),
    IOR(128, "ior", 0, -1, true, false, false, -1),
    LOR(129, "lor", 0, -2, true, false, false, -1),
    IXOR(130, "ixor", 0, -1, true, false, false, -1),
    LXOR(131, "lxor", 0, -2, true, false, false, -1),
    IINC(132, "iinc", 2, 0, true, false, false, 0),

    // === Conversions (133-147) ===
    I2L(133, "i2l", 0, 1, true, false, false, -1),
    I2F(134, "i2f", 0, 0, true, false, false, -1),
    I2D(135, "i2d", 0, 1, true, false, false, -1),
    L2I(136, "l2i", 0, -1, true, false, false, -1),
    L2F(137, "l2f", 0, -1, true, false, false, -1),
    L2D(138, "l2d", 0, 0, true, false, false, -1),
    F2I(139, "f2i", 0, 0, true, false, false, -1),
    F2L(140, "f2l", 0, 1, true, false, false, -1),
    F2D(141, "f2d", 0, 1, true, false, false, -1),
    D2I(142, "d2i", 0, -1, true, false, false, -1),
    D2L(143, "d2l", 0, 0, true, false, false, -1),
    D2F(144, "d2f", 0, -1, true, false, false, -1),
    I2B(145, "i2b", 0, 0, true, false, false, -1),
    I2C(146, "i2c", 0, 0, true, false, false, -1),
    I2S(147, "i2s", 0, 0, true, false, false, -1),

    // === Comparisons (148-152) ===
    LCMP(148, "lcmp", 0, -3, true, false, false, -1),
    FCMPL(149, "fcmpl", 0, -1, true, false, false, -1),
    FCMPG(150, "fcmpg", 0, -1, true, false, false, -1),
    DCMPL(151, "dcmpl", 0, -3, true, false, false, -1),
    DCMPG(152, "dcmpg", 0, -3, true, false, false, -1),

    // === Branch instructions (153-168) ===
    IFEQ(153, "ifeq", 2, -1, false, false, true, -1),
    IFNE(154, "ifne", 2, -1, false, false, true, -1),
    IFLT(155, "iflt", 2, -1, false, false, true, -1),
    IFGE(156, "ifge", 2, -1, false, false, true, -1),
    IFGT(157, "ifgt", 2, -1, false, false, true, -1),
    IFLE(158, "ifle", 2, -1, false, false, true, -1),
    IF_ICMPEQ(159, "if_icmpeq", 2, -2, false, false, true, -1),
    IF_ICMPNE(160, "if_icmpne", 2, -2, false, false, true, -1),
    IF_ICMPLT(161, "if_icmplt", 2, -2, false, false, true, -1),
    IF_ICMPGE(162, "if_icmpge", 2, -2, false, false, true, -1),
    IF_ICMPGT(163, "if_icmpgt", 2, -2, false, false, true, -1),
    IF_ICMPLE(164, "if_icmple", 2, -2, false, false, true, -1),
    IF_ACMPEQ(165, "if_acmpeq", 2, -2, false, false, true, -1),
    IF_ACMPNE(166, "if_acmpne", 2, -2, false, false, true, -1),
    GOTO(167, "goto", 2, 0, false, false, true, -1),
    JSR(168, "jsr", 2, 1, false, false, true, -1),

    // === Returns (172-177) ===
    IRETURN(172, "ireturn", 0, -1, false, true, false, -1),
    LRETURN(173, "lreturn", 0, -2, false, true, false, -1),
    FRETURN(174, "freturn", 0, -1, false, true, false, -1),
    DRETURN(175, "dreturn", 0, -2, false, true, false, -1),
    ARETURN(176, "areturn", 0, -1, false, true, false, -1),
    RETURN(177, "return", 0, 0, false, true, false, -1),

    // === Field access (178-181) ===
    GETSTATIC(178, "getstatic", 2, 0, true, false, false, -1),
    PUTSTATIC(179, "putstatic", 2, 0, true, false, false, -1),
    GETFIELD(180, "getfield", 2, 0, true, false, false, -1),
    PUTFIELD(181, "putfield", 2, 0, true, false, false, -1),

    // === Method invocation (182-185) ===
    INVOKEVIRTUAL(182, "invokevirtual", 2, 0, true, false, false, -1),
    INVOKESPECIAL(183, "invokespecial", 2, 0, true, false, false, -1),
    INVOKESTATIC(184, "invokestatic", 2, 0, true, false, false, -1),
    INVOKEINTERFACE(185, "invokeinterface", 4, 0, true, false, false, -1),

    // === Object/Array creation (187-190) ===
    NEW(187, "new", 2, 1, true, false, false, -1),
    NEWARRAY(188, "newarray", 1, 0, true, false, false, -1),
    ANEWARRAY(189, "anewarray", 2, 0, true, false, false, -1),
    ARRAYLENGTH(190, "arraylength", 0, 0, true, false, false, -1),

    // === Throw/Cast/InstanceOf (191-193) ===
    ATHROW(191, "athrow", 0, -1, false, true, false, -1),
    CHECKCAST(192, "checkcast", 2, 0, true, false, false, -1),
    INSTANCEOF(193, "instanceof", 2, 0, true, false, false, -1),

    // === Synchronization (194-195) ===
    MONITORENTER(194, "monitorenter", 0, -1, true, false, false, -1),
    MONITOREXIT(195, "monitorexit", 0, -1, true, false, false, -1),

    // === Extended branch (198-199) ===
    IFNULL(198, "ifnull", 2, -1, false, false, true, -1),
    IFNONNULL(199, "ifnonnull", 2, -1, false, false, true, -1),

    // Switch (variable-length, decoded specially in InstructionDecoder)
    TABLESWITCH(170, "tableswitch", 0, -1, false, true, false, -1),
    LOOKUPSWITCH(171, "lookupswitch", 0, -1, false, true, false, -1);

    // Phase 1b adds: RET(169), WIDE(196),
    // MULTIANEWARRAY(197), GOTO_W(200), INVOKEDYNAMIC(186)

    private static final Map<Integer, Opcode> BY_CODE = new HashMap<>();

    static {
        for (Opcode op : values()) {
            BY_CODE.put(op.code, op);
        }
    }

    private final int code;

    private final String mnemonic;

    private final int operandBytes;

    private final int stackDelta;

    private final boolean canFallThrough;

    private final boolean isTerminal;

    private final boolean isConditional;

    private final int implicitVarIndex;

    Opcode(int code, String mnemonic, int operandBytes, int stackDelta,
           boolean canFallThrough, boolean isTerminal, boolean isConditional,
           int implicitVarIndex) {
        this.code = code;
        this.mnemonic = mnemonic;
        this.operandBytes = operandBytes;
        this.stackDelta = stackDelta;
        this.canFallThrough = canFallThrough;
        this.isTerminal = isTerminal;
        this.isConditional = isConditional;
        this.implicitVarIndex = implicitVarIndex;
    }

    public static Opcode byCode(int code) {
        Opcode op = BY_CODE.get(code);
        if (op == null) {
            throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(code) + " (" + code + ")");
        }
        return op;
    }

    public int code() {return code;}

    public String mnemonic() {return mnemonic;}

    public int operandBytes() {return operandBytes;}

    public int stackDelta() {return stackDelta;}

    public boolean canFallThrough() {return canFallThrough;}

    public boolean isTerminal() {return isTerminal;}

    public boolean isConditional() {return isConditional;}

    public int implicitVarIndex() {return implicitVarIndex;}
}
