package com.bingbaihanji.bdec.cfg;

/**
 * 控制流边的类型枚举.
 * <p>
 * 定义了控制流图中各种边的类型,包括入口边,分支边,异常边等,
 * 用于区分不同语义的控制流转移.
 * </p>
 */
public enum EdgeKind {
    /** 入口边:从入口块指向第一个实际基本块 */
    ENTRY,
    /** 顺序执行边:从上一条指令自然流向下一指令(无跳转) */
    FALL_THROUGH,
    /** 条件为真时的分支边 */
    TRUE_BRANCH,
    /** 条件为假时的分支边 */
    FALSE_BRANCH,
    /** 无条件跳转边(goto指令) */
    GOTO,
    /** switch语句的case分支边 */
    SWITCH_CASE,
    /** switch语句的默认分支边 */
    SWITCH_DEFAULT,
    /** 异常处理边:从受保护区域指向异常处理器 */
    EXCEPTION,
    /** 返回边:指向出口块 */
    RETURN,
    /** 抛出边:由athrow指令指向出口块 */
    THROW
}
