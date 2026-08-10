package com.bingbaihanji.bdec.semantic;

/**
 * 语义注解标签枚举.
 *
 * <p>由 {@link SemanticReconstructor} 管线附加到 IR 指令上的语义标记.
 *
 * <p>每个标签代表在原始字节码 IR 中识别出的一个高级语义模式,
 * 供下游遍历({@code BlockReducer},{@code StatementEmitter})使用,
 * 以便生成正确的 Java 源代码.
 */
public enum SemanticTag {

    /** 对 {@code <init>} 的 invokespecial 调用 —— 构造函数委托 */
    CONSTRUCTOR_DELEGATION,

    /** 对同一类的构造函数委托:{@code this(...)} */
    THIS_CONSTRUCTOR,

    /** 对父类的构造函数委托:{@code super(...)} */
    SUPER_CONSTRUCTOR,

    /** 已被移除的 {@code Objects.requireNonNull} 或 {@code getClass()} 空检查调用 */
    NULL_CHECK_REMOVED,

    /** 包含 monitorenter → synchronized 方法体 → monitorexit 的同步块 */
    SYNCHRONIZED_BLOCK,

    /** 布尔类型方法中的返回指令 */
    BOOLEAN_RETURN,

    /** 应从构造函数中提取为字段级初始化器的字段初始化 */
    FIELD_INIT,

    /** 结果恰好被消费一次的表达式 —— 可内联到使用点的候选 */
    SINGLE_USE_INLINE,

    /** 静态方法调用的声明类.允许输出如 {@code Arrays.fill(...)} 而非仅 {@code fill(...)} */
    DECLARING_CLASS,

    /** 源自 invokedynamic 字节码的 INVOKE IR 指令.
     *  下游遍历可用此标签生成 lambda 表达式或方法引用. */
    INDY,
}
