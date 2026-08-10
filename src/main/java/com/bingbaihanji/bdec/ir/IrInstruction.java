package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.semantic.SemanticAnnotation;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * IR指令.
 * <p>
 * 中间表示(IR)中的一条指令,是从底层字节码指令(栈式)抽象而来的寄存器式指令.
 * 每条指令包含操作码({@link IrOpcode}),结果类型,操作数列表,
 * 源字节码偏移量,所属基本块ID以及可选的元数据(原始字节码操作码,名称提示).
 * 支持语义注解以及大量便捷的静态工厂方法.
 * </p>
 */
public class IrInstruction {

    /** 指令唯一标识符 */
    private final int id;

    /** IR操作码 */
    private final IrOpcode opcode;

    /** 指令结果类型 */
    private final JavaType resultType;

    /** 操作数列表(不可变) */
    private final List<Value> operands;

    /** 源字节码偏移量 */
    private final int sourceOffset;

    /** 所属基本块ID */
    private final int blockId;

    /** 原始JVM字节码操作码(如0x60=IADD, 0x64=ISUB).0表示无原始操作码(合成的PHI等). */
    private final int originalOpcode;

    /** 已解析的名称提示——来自常量池的字段名或方法名. */
    private final String nameHint;

    /** 语义注解列表,由语义重建管道附加. */
    private List<SemanticAnnotation> annotations;

    /** 该指令产生的结果值 */
    private Value resultValue;

    /**
     * 构造一条IR指令(含元数据).
     *
     * @param id             指令ID
     * @param opcode         IR操作码
     * @param resultType     结果类型
     * @param operands       操作数列表
     * @param sourceOffset   源字节码偏移量
     * @param blockId        基本块ID
     * @param originalOpcode 原始JVM字节码操作码
     * @param nameHint       名称提示
     */
    public IrInstruction(int id, IrOpcode opcode, JavaType resultType,
                         List<Value> operands, int sourceOffset, int blockId,
                         int originalOpcode, String nameHint) {
        this.id = id;
        this.opcode = opcode;
        this.resultType = resultType;
        this.operands = List.copyOf(operands);
        this.sourceOffset = sourceOffset;
        this.blockId = blockId;
        this.originalOpcode = originalOpcode;
        this.nameHint = nameHint;
    }

    /**
     * 构造一条IR指令(不含元数据),保持向后兼容.
     */
    public IrInstruction(int id, IrOpcode opcode, JavaType resultType,
                         List<Value> operands, int sourceOffset, int blockId) {
        this(id, opcode, resultType, operands, sourceOffset, blockId, 0, null);
    }

    // ── 工厂方法 ───────────────────────────────────────────────────────

    /**
     * 创建变量加载指令(LOAD).
     */
    public static IrInstruction load(int id, Variable var, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.LOAD, var.type(), List.of(var),
                offset, blockId, 0, null);
    }

    /**
     * 创建变量存储指令(STORE).
     */
    public static IrInstruction store(int id, Variable var, Value value, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.STORE, var.type(), List.of(var, value),
                offset, blockId, 0, null);
    }

    /**
     * 创建二元运算指令(含原始字节码操作码).
     */
    public static IrInstruction binary(int id, IrOpcode op, Value left, Value right,
                                       JavaType resultType, int offset, int blockId,
                                       int originalOpcode) {
        return new IrInstruction(id, op, resultType, List.of(left, right),
                offset, blockId, originalOpcode, null);
    }

    /**
     * 创建二元运算指令(向后兼容,无原始字节码操作码).
     */
    public static IrInstruction binary(int id, IrOpcode op, Value left, Value right,
                                       JavaType resultType, int offset, int blockId) {
        return binary(id, op, left, right, resultType, offset, blockId, 0);
    }

    /**
     * 创建方法调用指令(INVOKE),含方法名.
     */
    public static IrInstruction invoke(int id, Value target, List<Value> args,
                                       JavaType returnType, int offset, int blockId,
                                       String methodName) {
        List<Value> operands = new java.util.ArrayList<>();
        if (target != null) {
            operands.add(target);
        }
        operands.addAll(args);
        return new IrInstruction(id, IrOpcode.INVOKE, returnType, operands,
                offset, blockId, 0, methodName);
    }

    /**
     * 创建方法调用指令(INVOKE),无方法名(向后兼容).
     */
    public static IrInstruction invoke(int id, Value target, List<Value> args,
                                       JavaType returnType, int offset, int blockId) {
        return invoke(id, target, args, returnType, offset, blockId, null);
    }

    /**
     * 创建字段加载指令(FIELD_LOAD),含字段名.
     */
    public static IrInstruction fieldLoad(int id, Value obj, JavaType fieldType,
                                          int offset, int blockId, String fieldName) {
        List<Value> ops = obj != null ? List.of(obj) : List.of();
        return new IrInstruction(id, IrOpcode.FIELD_LOAD, fieldType, ops,
                offset, blockId, 0, fieldName);
    }

    /**
     * 创建字段加载指令(FIELD_LOAD),无字段名(向后兼容).
     */
    public static IrInstruction fieldLoad(int id, Value obj, JavaType fieldType,
                                          int offset, int blockId) {
        return fieldLoad(id, obj, fieldType, offset, blockId, null);
    }

    /**
     * 创建字段存储指令(FIELD_STORE),含字段名.
     */
    public static IrInstruction fieldStore(int id, Value obj, Value value,
                                           int offset, int blockId, String fieldName) {
        List<Value> ops = obj != null ? List.of(obj, value) : List.of(value);
        return new IrInstruction(id, IrOpcode.FIELD_STORE, value.type(), ops,
                offset, blockId, 0, fieldName);
    }

    /**
     * 创建字段存储指令(FIELD_STORE),无字段名(向后兼容).
     */
    public static IrInstruction fieldStore(int id, Value obj, Value value,
                                           int offset, int blockId) {
        return fieldStore(id, obj, value, offset, blockId, null);
    }

    /**
     * 创建返回指令(RETURN).
     */
    public static IrInstruction returnInsn(int id, Value value, int offset, int blockId) {
        List<Value> ops = value != null ? List.of(value) : List.of();
        JavaType t = value != null ? value.type() : JavaType.VOID;
        return new IrInstruction(id, IrOpcode.RETURN, t, ops, offset, blockId, 0, null);
    }

    /**
     * 创建对象创建指令(NEW).
     */
    public static IrInstruction newInsn(int id, JavaType type, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.NEW, type, List.of(), offset, blockId, 0, null);
    }

    /**
     * 创建类型转换指令(CAST),含原始字节码操作码.
     */
    public static IrInstruction cast(int id, Value value, JavaType targetType,
                                     int offset, int blockId, int originalOpcode) {
        return new IrInstruction(id, IrOpcode.CAST, targetType, List.of(value),
                offset, blockId, originalOpcode, null);
    }

    /**
     * 创建类型转换指令(CAST),无原始字节码操作码(向后兼容).
     */
    public static IrInstruction cast(int id, Value value, JavaType targetType,
                                     int offset, int blockId) {
        return cast(id, value, targetType, offset, blockId, 0);
    }

    /**
     * 创建常量指令(CONST).
     */
    public static IrInstruction constInsn(int id, ConstantValue value, int offset, int blockId) {
        return new IrInstruction(id, IrOpcode.CONST, value.type(), List.of(value),
                offset, blockId, 0, null);
    }

    // ── 属性访问器 ────────────────────────────────────────────────────

    /**
     * 将JVM字节码操作码映射为对应的二元运算符.
     * 涵盖算术运算,位运算,位移运算,比较运算和空值比较.
     *
     * @param bc JVM字节码操作码
     * @return 对应的二元运算符,非二元/比较操作码返回 {@code null}
     */
    public static BinaryOperator binaryOpFromBytecode(int bc) {
        return switch (bc) {
            // 算术运算
            case 0x60 -> BinaryOperator.ADD;    // IADD
            case 0x64 -> BinaryOperator.SUB;    // ISUB
            case 0x68 -> BinaryOperator.MUL;    // IMUL
            case 0x6c -> BinaryOperator.DIV;    // IDIV
            case 0x70 -> BinaryOperator.REM;    // IREM
            case 0x61 -> BinaryOperator.ADD;    // LADD
            case 0x65 -> BinaryOperator.SUB;    // LSUB
            case 0x69 -> BinaryOperator.MUL;    // LMUL
            case 0x6d -> BinaryOperator.DIV;    // LDIV
            case 0x71 -> BinaryOperator.REM;    // LREM
            case 0x62 -> BinaryOperator.ADD;    // FADD
            case 0x66 -> BinaryOperator.SUB;    // FSUB
            case 0x6a -> BinaryOperator.MUL;    // FMUL
            case 0x6e -> BinaryOperator.DIV;    // FDIV
            case 0x72 -> BinaryOperator.REM;    // FREM
            case 0x63 -> BinaryOperator.ADD;    // DADD
            case 0x67 -> BinaryOperator.SUB;    // DSUB
            case 0x6b -> BinaryOperator.MUL;    // DMUL
            case 0x6f -> BinaryOperator.DIV;    // DDIV
            case 0x73 -> BinaryOperator.REM;    // DREM
            // 位运算
            case 0x7e -> BinaryOperator.BIT_AND; // IAND
            case 0x7f -> BinaryOperator.BIT_AND; // LAND
            case 0x80 -> BinaryOperator.BIT_OR;  // IOR
            case 0x81 -> BinaryOperator.BIT_OR;  // LOR
            case 0x82 -> BinaryOperator.BIT_XOR; // IXOR
            case 0x83 -> BinaryOperator.BIT_XOR; // LXOR
            // 位移运算
            case 0x78 -> BinaryOperator.SHL;     // ISHL
            case 0x79 -> BinaryOperator.SHL;     // LSHL
            case 0x7a -> BinaryOperator.SHR;     // ISHR
            case 0x7b -> BinaryOperator.SHR;     // LSHR
            case 0x7c -> BinaryOperator.USHR;    // IUSHR
            case 0x7d -> BinaryOperator.USHR;    // LUSHR
            // 整数比较
            case 0x9f -> BinaryOperator.EQ;      // IF_ICMPEQ
            case 0xa0 -> BinaryOperator.NE;      // IF_ICMPNE
            case 0xa1 -> BinaryOperator.LT;      // IF_ICMPLT
            case 0xa2 -> BinaryOperator.GE;      // IF_ICMPGE
            case 0xa3 -> BinaryOperator.GT;      // IF_ICMPGT
            case 0xa4 -> BinaryOperator.LE;      // IF_ICMPLE
            // 引用比较
            case 0xa5 -> BinaryOperator.EQ;      // IF_ACMPEQ
            case 0xa6 -> BinaryOperator.NE;      // IF_ACMPNE
            // 零值比较
            case 0x99 -> BinaryOperator.EQ;      // IFEQ
            case 0x9a -> BinaryOperator.NE;      // IFNE
            case 0x9b -> BinaryOperator.LT;      // IFLT
            case 0x9c -> BinaryOperator.GE;      // IFGE
            case 0x9d -> BinaryOperator.GT;      // IFGT
            case 0x9e -> BinaryOperator.LE;      // IFLE
            // 空值比较
            case 0xc6 -> BinaryOperator.EQ;      // IFNULL  (ref == null)
            case 0xc7 -> BinaryOperator.NE;      // IFNONNULL (ref != null)
            default -> null;
        };
    }

    /** @return 指令ID */
    public int id() {return id;}

    /** @return IR操作码 */
    public IrOpcode opcode() {return opcode;}

    /** @return 指令结果类型 */
    public JavaType resultType() {return resultType;}

    /** @return 操作数列表 */
    public List<Value> operands() {return operands;}

    /** @return 源字节码偏移量 */
    public int sourceOffset() {return sourceOffset;}

    /** @return 所属基本块ID */
    public int blockId() {return blockId;}

    /** @return 原始JVM字节码操作码,0表示合成指令 */
    public int originalOpcode() {return originalOpcode;}

    /**
     * 获取已解析的字段名或方法名.
     *
     * @return 名称提示,未解析时返回 {@code null}
     */
    public String nameHint() {return nameHint;}

    /**
     * 获取语义注解列表.
     *
     * @return 语义注解列表,无注解时返回空列表
     */
    public List<SemanticAnnotation> annotations() {
        return annotations != null ? annotations : Collections.emptyList();
    }

    /**
     * 为指令附加一条语义注解.
     *
     * @param ann 语义注解
     */
    public void addAnnotation(SemanticAnnotation ann) {
        if (annotations == null) {
            annotations = new ArrayList<>(2);
        }
        annotations.add(ann);
    }

    /**
     * 检查指令是否具有指定的语义标签.
     *
     * @param tag 语义标签
     * @return 如果有该标签则返回 {@code true}
     */
    public boolean hasTag(com.bingbaihanji.bdec.semantic.SemanticTag tag) {
        if (annotations == null) {
            return false;
        }
        for (SemanticAnnotation a : annotations) {
            if (a.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取指定标签的第一条语义注解.
     *
     * @param tag 语义标签
     * @return 语义注解,若无则返回 {@code null}
     */
    public SemanticAnnotation getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag tag) {
        if (annotations == null) {
            return null;
        }
        for (SemanticAnnotation a : annotations) {
            if (a.is(tag)) {
                return a;
            }
        }
        return null;
    }

    /** @return 该指令产生的结果值 */
    public Value resultValue() {return resultValue;}

    /** 设置该指令产生的结果值 */
    public void setResultValue(Value v) {this.resultValue = v;}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(": ").append(opcode);
        if (nameHint != null) {
            sb.append(" '").append(nameHint).append("'");
        }
        sb.append(" ").append(operands).append(" -> ").append(resultType);
        return sb.toString();
    }
}
