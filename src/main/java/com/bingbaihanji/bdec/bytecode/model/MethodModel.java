package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        List<LocalVariableEntry> localVarEntries
) {

    /** 向后兼容的构造函数,不含签名与局部变量表信息. */
    public MethodModel(int accessFlags, String name, String descriptor,
                       JavaType returnType, JavaType[] parameterTypes,
                       List<Instruction> instructions,
                       List<ExceptionHandlerModel> exceptionHandlers,
                       int maxStack, int maxLocals) {
        this(accessFlags, name, descriptor, returnType, parameterTypes,
                instructions, exceptionHandlers, maxStack, maxLocals, "",
                Collections.emptyMap(), Collections.emptyList());
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
                Collections.emptyMap(), Collections.emptyList());
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
        return localVarNames.get(slot);
    }

    /**
     * 判断该方法是否为抽象方法.
     *
     * @return 若 {@code ACC_ABSTRACT} 标志位被设置则返回 {@code true}
     */
    public boolean isAbstract() {return (accessFlags & 0x0400) != 0;}

    /**
     * 判断该方法是否为本地方法.
     *
     * @return 若 {@code ACC_NATIVE} 标志位被设置则返回 {@code true}
     */
    public boolean isNative() {return (accessFlags & 0x0100) != 0;}

    /**
     * 判断该方法是否为静态方法.
     *
     * @return 若 {@code ACC_STATIC} 标志位被设置则返回 {@code true}
     */
    public boolean isStatic() {return (accessFlags & 0x0008) != 0;}
}
