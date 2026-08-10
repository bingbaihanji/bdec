package com.bingbaihanji.bdec.ir;

/**
 * IR操作码枚举.
 * <p>
 * 定义了中间表示(IR)中所有指令的操作类型,从底层的字节码指令抽象而来.
 * 包括常量,加载/存储,算术/逻辑运算,类型转换,方法调用,
 * 对象/数组操作,控制流相关和监视器指令等.
 * </p>
 */
public enum IrOpcode {
    /** 常量值 */
    CONST,
    /** 字符串常量 */
    CONST_STRING,
    /** 变量加载 */
    LOAD,
    /** 变量存储 */
    STORE,
    /** Phi函数节点(SSA形式中的控制流汇合点) */
    PHI,
    /** 栈加载(模拟操作数栈的中间栈操作) */
    STACK_LOAD,
    /** 栈存储(模拟操作数栈的中间栈操作) */
    STACK_STORE,
    /** 一元运算(取负,取反等) */
    UNARY,
    /** 二元运算(加减乘除,位运算等) */
    BINARY,
    /** 比较运算(lcmp,fcmpl等) */
    COMPARE,
    /** 类型转换 */
    CAST,
    /** instanceof类型检测 */
    INSTANCE_OF,
    /** 字段加载(GETFIELD/GETSTATIC) */
    FIELD_LOAD,
    /** 字段存储(PUTFIELD/PUTSTATIC) */
    FIELD_STORE,
    /** 数组元素加载 */
    ARRAY_LOAD,
    /** 数组元素存储 */
    ARRAY_STORE,
    /** 数组长度获取 */
    ARRAY_LENGTH,
    /** 方法调用 */
    INVOKE,
    /** 对象创建(NEW) */
    NEW,
    /** 数组创建 */
    NEW_ARRAY,
    /** 基本类型数组创建 */
    NEW_PRIMITIVE_ARRAY,
    /** 条件分支(if系列指令) */
    CONDITION,
    /** switch分支 */
    SWITCH,
    /** 方法返回 */
    RETURN,
    /** 异常抛出 */
    THROW,
    /** 三元表达式(?:) */
    TERNARY,
    /** 整型递增(IINC) */
    INC,
    /** 监视器进入(MONITORENTER) */
    MONITOR_ENTER,
    /** 监视器退出(MONITOREXIT) */
    MONITOR_EXIT
}
