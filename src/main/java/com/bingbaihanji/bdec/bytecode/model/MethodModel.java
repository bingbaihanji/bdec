package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 方法模型.
 *
 * <p>封装 Java 类文件中一个方法({@code method_info})的完整信息,包括
 * 访问标志,名称,描述符,返回类型,参数类型,字节码指令列表,
 * 异常处理器表,操作数栈与局部变量最大容量,泛型签名和局部变量名映射.
 *
 * @param accessFlags       方法访问标志({@code ACC_PUBLIC},{@code ACC_STATIC} 等)
 * @param name              方法名称
 * @param descriptor        方法描述符(如 {@code (II)I})
 * @param returnType        方法返回类型
 * @param parameterTypes    方法参数类型数组
 * @param instructions      字节码指令列表,若为抽象或本地方法则为 {@code null}
 * @param exceptionHandlers 异常处理器列表
 * @param maxStack          操作数栈最大深度
 * @param maxLocals         局部变量表最大槽位数
 * @param signature         方法级泛型签名属性,若无则为空字符串
 * @param localVarNames     局部变量索引到变量名的映射(来自 LVT 属性,保留用于向后兼容)
 * @param localVarEntries   作用域感知的局部变量表条目列表(用于按字节码偏移量查找变量名)
 * @param declaredExceptions 方法声明中 throws 的异常类型列表(内部名称)
 * @param annotationDefault  注解方法元素的默认值(AnnotationDefault 属性,
 *                           普通方法为 null)
 * @param annotations        方法上的注解(RuntimeVisible/InvisibleAnnotations),
 *                           无则为空列表
 * @param parameterAnnotations 参数级注解(RuntimeVisibleParameterAnnotations),
 *                             按描述符参数顺序每参数一个列表,无则为空列表
 * @param typeAnnotations      JSR-308 类型注解(RuntimeVisibleTypeAnnotations),
 *                             无则为空列表
 */
public record MethodModel(
        int accessFlags,
        String name,
        String descriptor,
        JavaType returnType,
        JavaType[] parameterTypes,
        List<Instruction> instructions,
        List<ExceptionHandlerModel> exceptionHandlers,
        int maxStack,
        int maxLocals,
        String signature,
        Map<Integer, String> localVarNames,
        List<LocalVariableEntry> localVarEntries,
        List<String> declaredExceptions,
        Object annotationDefault,
        List<AnnotationEntry> annotations,
        List<List<AnnotationEntry>> parameterAnnotations,
        List<TypeAnnotationEntry> typeAnnotations
) {

    /** 向后兼容的构造函数,不含签名与局部变量表信息. */
    public MethodModel(int accessFlags, String name, String descriptor,
                       JavaType returnType, JavaType[] parameterTypes,
                       List<Instruction> instructions,
                       List<ExceptionHandlerModel> exceptionHandlers,
                       int maxStack, int maxLocals) {
        this(accessFlags, name, descriptor, returnType, parameterTypes,
                instructions, exceptionHandlers, maxStack, maxLocals, "",
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                null, List.of(), List.of(), List.of());
    }

    /** 包含签名但不含局部变量表的构造函数. */
    public MethodModel(int accessFlags, String name, String descriptor,
                       JavaType returnType, JavaType[] parameterTypes,
                       List<Instruction> instructions,
                       List<ExceptionHandlerModel> exceptionHandlers,
                       int maxStack, int maxLocals,
                       String signature) {
        this(accessFlags, name, descriptor, returnType, parameterTypes,
                instructions, exceptionHandlers, maxStack, maxLocals, signature,
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                null, List.of(), List.of(), List.of());
    }

    /**
     * 按字节码偏移量查找局部变量名(作用域感知).
     *
     * <p>遍历局部变量表条目,返回在给定偏移量处第一个匹配的槽位名称.
     * 若该偏移量处无匹配条目,回退到 {@code localVarNames} 映射.
     *
     * @param slot 局部变量槽位索引
     * @param pc   字节码偏移量
     * @return 变量名,若未找到则返回 {@code null}
     */
    public String lookupVarName(int slot, int pc) {
        for (LocalVariableEntry entry : localVarEntries) {
            if (entry.slot() == slot && entry.covers(pc)) {
                return entry.name();
            }
        }
        // 无精确作用域匹配时,走 (pc,pc+4] 窗口 + startPc<=pc 回退(见 findWindowOrFallback).
        LocalVariableEntry entry = findWindowOrFallback(slot, pc, e -> true);
        // 无任何匹配条目.不退回 flat map(last-wins 语义会在槽位复用时
        // 产生跨作用域的错误命名),由调用方使用通用名称.
        return entry != null ? entry.name() : null;
    }

    /**
     * 查找局部变量上的 JSR-308 类型注解(RuntimeVisibleTypeAnnotations 0x40/0x41).
     *
     * <p>类型注解条目的目标信息携带 LVT 表索引与作用域 (startPc, length).
     * 索引指向 LocalVariableTable 中的条目位置(本类按解析顺序保存),
     * 据此得到 (slot, 条目 startPc/名称),再与 STORE 点对齐——
     * 与 {@link #lookupVarName} 相同的窗口规则:精确覆盖或
     * (pc, pc+4] 窗口(条目 startPc 指向 STORE 之后),按名称对齐防槽位复用误配.</p>
     *
     * @param slot    局部变量槽位索引
     * @param pc      字节码偏移量(STORE 位置)
     * @param varName 变量名(窗口匹配的消歧,可为 null)
     * @return 匹配的类型注解条目列表(可能为空)
     */
    public List<TypeAnnotationEntry> lookupVarTypeAnnotations(int slot, int pc, String varName) {
        List<TypeAnnotationEntry> result = new ArrayList<>();
        for (TypeAnnotationEntry ta : typeAnnotations) {
            if (ta.targetType() != TypeAnnotationEntry.TARGET_LOCAL_VARIABLE
                    && ta.targetType() != 0x41) {
                continue;
            }
            int[] ti = ta.targetInfo();
            if (ti == null || ti.length < 1) {
                continue;
            }
            for (int i = 0; i < ti[0]; i++) {
                int startPc = ti[1 + i * 3];
                int length = ti[2 + i * 3];
                int lvtIndex = ti[3 + i * 3];
                if (lvtIndex < 0 || lvtIndex >= localVarEntries.size()) {
                    continue;
                }
                LocalVariableEntry entry = localVarEntries.get(lvtIndex);
                if (entry.slot() != slot) {
                    continue;
                }
                boolean nameOk = varName == null || varName.equals(entry.name());
                boolean inScope = (pc >= startPc && pc < startPc + length)
                        || (nameOk && startPc > pc && startPc <= pc + 4);
                if (inScope) {
                    result.add(ta);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 查找指令偏移量目标类型的 JSR-308 类型注解
     * (0x43 instanceof / 0x44 new / 0x47 cast).
     *
     * <p>此类条目的 target_info 为 [offset](cast 为 [offset, type_argument_index]),
     * 指向触发该类型的字节码指令(checkcast/new/anewarray/instanceof) 的偏移量.</p>
     *
     * @param targetType 目标类型(0x43/0x44/0x47)
     * @param offset     字节码指令偏移量
     * @return 匹配的类型注解条目列表(可能为空)
     */
    public List<TypeAnnotationEntry> lookupOffsetTypeAnnotations(int targetType, int offset) {
        List<TypeAnnotationEntry> result = new ArrayList<>();
        for (TypeAnnotationEntry ta : typeAnnotations) {
            if (ta.targetType() != targetType) {
                continue;
            }
            int[] ti = ta.targetInfo();
            if (ti != null && ti.length >= 1 && ti[0] == offset) {
                result.add(ta);
            }
        }
        return result;
    }

    /**
     * 按字节码偏移量查找局部变量的泛型签名(LocalVariableTypeTable).
     *
     * <p>查找层次与 {@link #lookupVarName} 相同:精确作用域覆盖、
     * (pc, pc+4] 窗口(条目 startPc 指向 STORE 之后)、
     * startPc 不晚于 pc 的最近条目.窗口与回退层按变量名对齐,
     * 防止槽位复用时把前一个变量的泛型签名赋给新变量.</p>
     *
     * @param slot    局部变量槽位索引
     * @param pc      字节码偏移量
     * @param varName 变量名(用于窗口/回退层的消歧,可为 null)
     * @return 泛型签名,若无则返回 {@code null}
     */
    public String lookupVarTypeSignature(int slot, int pc, String varName) {
        for (LocalVariableEntry entry : localVarEntries) {
            if (entry.slot() == slot && entry.covers(pc)
                    && entry.typeSignature() != null) {
                return entry.typeSignature();
            }
        }
        // STORE 位于条目 startPc 之前(窗口回退),按名称对齐防槽位复用误配.
        LocalVariableEntry entry = findWindowOrFallback(slot, pc,
                e -> e.typeSignature() != null
                        && (varName == null || varName.equals(e.name())));
        return entry != null ? entry.typeSignature() : null;
    }

    /**
     * 在 LVT 中按 (pc, pc+4] 窗口与回退规则查找最佳条目.
     *
     * <p>STORE 指令位于 pc 时,变量的 LVT 条目 startPc 指向 STORE 之后的位置
     * (JVMS 规定 startPc 是变量初始化之后的首个偏移),故需在窗口 (pc, pc+4] 内
     * 查找 (istore/astore 1-2 字节,wistore 等更长).窗口内优先 startPc 最小的条目——
     * 相邻槽位复用场景中两个条目的 startPc 可能都在窗口内,最小者属于本次 STORE.
     * 此窗口检查必须在 startPc<=pc 的回退之前,否则后续变量的 STORE 会错误匹配到
     * 已结束的前一个变量上.窗口无匹配时,回退到 startPc<=pc 且 startPc 最大的条目
     * (比 flat map 的 last-wins 语义更准确,能正确处理同一槽位在 try 块内/外被
     * 不同变量复用的情况).</p>
     *
     * @param slot   局部变量槽位索引
     * @param pc     字节码偏移量(STORE 位置)
     * @param filter 额外条目过滤条件(如要求 typeSignature 非空或按名称对齐)
     * @return 最佳条目,无匹配则返回 {@code null}
     */
    private LocalVariableEntry findWindowOrFallback(int slot, int pc,
                                                     Predicate<LocalVariableEntry> filter) {
        LocalVariableEntry windowBest = null;
        for (LocalVariableEntry entry : localVarEntries) {
            if (entry.slot() == slot && entry.startPc() > pc && entry.startPc() <= pc + 4
                    && filter.test(entry)
                    && (windowBest == null || entry.startPc() < windowBest.startPc())) {
                windowBest = entry;
            }
        }
        if (windowBest != null) {
            return windowBest;
        }
        LocalVariableEntry best = null;
        for (LocalVariableEntry entry : localVarEntries) {
            if (entry.slot() == slot && entry.startPc() <= pc
                    && filter.test(entry)
                    && (best == null || entry.startPc() > best.startPc())) {
                best = entry;
            }
        }
        return best;
    }

    /**
     * 判断该方法是否为抽象方法.
     *
     * @return 若 {@code ACC_ABSTRACT} 标志位被设置则返回 {@code true}
     */
    public boolean isAbstract() {return (accessFlags & AccessFlags.ACC_ABSTRACT) != 0;}

    /**
     * 判断该方法是否为本地方法.
     *
     * @return 若 {@code ACC_NATIVE} 标志位被设置则返回 {@code true}
     */
    public boolean isNative() {return (accessFlags & AccessFlags.ACC_NATIVE) != 0;}

    /**
     * 判断该方法是否为静态方法.
     *
     * @return 若 {@code ACC_STATIC} 标志位被设置则返回 {@code true}
     */
    public boolean isStatic() {return (accessFlags & AccessFlags.ACC_STATIC) != 0;}
}
