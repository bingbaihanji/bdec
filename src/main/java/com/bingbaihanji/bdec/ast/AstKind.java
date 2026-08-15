package com.bingbaihanji.bdec.ast;

/**
 * AST节点类型枚举.
 * <p>
 * 定义了抽象语法树中所有节点类型的枚举常量,涵盖编译单元,类型声明,语句,表达式等
 * 各种语法结构.每种节点类型对应AST中的一个具体节点类.
 * </p>
 */
public enum AstKind {
    /** 编译单元(源文件) */
    COMPILATION_UNIT,
    /** 模块声明(module-info.java) */
    MODULE_DECL,
    /** 类型声明(类,接口,枚举,注解) */
    TYPE_DECLARATION,
    /** 代码块 */
    BLOCK,
    /** if 条件语句 */
    IF,
    /** 循环语句 */
    LOOP,
    /** switch 语句 */
    SWITCH,
    /** try-catch-finally 异常处理语句 */
    TRY,
    /** return 返回语句 */
    RETURN,
    /** throw 抛出异常语句 */
    THROW,
    /** 表达式语句 */
    EXPRESSION_STMT,
    /** break 跳出语句 */
    BREAK,
    /** continue 继续语句 */
    CONTINUE,
    /** 带标签跳转语句(goto label;——不可归约 CFG 兜底) */
    GOTO,
    /** 标签声明语句(label: ——不可归约 CFG 兜底) */
    LABEL,
    /** 变量声明语句 */
    VARIABLE_DECL,
    /** synchronized 同步语句 */
    SYNCHRONIZED,
    /** 字面量表达式 */
    LITERAL,
    /** 变量引用表达式 */
    VARIABLE,
    /** 二元运算表达式 */
    BINARY,
    /** 一元运算表达式 */
    UNARY,
    /** 赋值表达式 */
    ASSIGNMENT,
    /** 三元条件表达式 */
    CONDITIONAL,
    /** 方法调用表达式 */
    INVOCATION,
    /** 字段访问表达式 */
    FIELD_ACCESS,
    /** 数组访问表达式 */
    ARRAY_ACCESS,
    /** 类型转换表达式 */
    CAST,
    /** instanceof 类型判断表达式 */
    INSTANCE_OF,
    /** new 创建对象表达式 */
    NEW,
    /** lambda 表达式 */
    LAMBDA,
    /** switch 表达式 */
    SWITCH_EXPR,
    /** 字段声明 */
    FIELD_DECL,
    /** 方法声明 */
    METHOD_DECL,
    /** 构造函数 */
    CONSTRUCTOR,
    /** 静态初始化块 */
    STATIC_INIT,
    /** 模式匹配 switch 的 case 标签(如 {@code case Integer i when i > 0}) */
    PATTERN_LABEL
}
