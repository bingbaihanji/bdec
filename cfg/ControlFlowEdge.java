package com.bingbaihanji.bdec.cfg;

/**
 * 控制流边记录.
 * <p>
 * 表示控制流图中两个基本块之间的有向边,记录源块,目标块,边的类型以及
 * switch case值或异常类型等附加信息.
 * </p>
 *
 * @param source    源基本块
 * @param target    目标基本块
 * @param kind      边的类型
 * @param switchKey switch case的键值(非switch边时为-1)
 * @param catchType 捕获的异常类型全限定名(非异常边时为 {@code null})
 */
public record ControlFlowEdge(
        BasicBlock source,
        BasicBlock target,
        EdgeKind kind,
        int switchKey,
        String catchType
) {

    /**
     * 创建一条顺序执行(落入)边.
     *
     * @param source 源基本块
     * @param target 目标基本块
     * @return 顺序执行边
     */
    public static ControlFlowEdge fallThrough(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.FALL_THROUGH, -1, null);
    }

    /**
     * 创建一条无条件跳转边(goto).
     *
     * @param source 源基本块
     * @param target 目标基本块
     * @return 无条件跳转边
     */
    public static ControlFlowEdge gotoEdge(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.GOTO, -1, null);
    }

    /**
     * 创建一条条件为真时的分支边.
     *
     * @param source 源基本块
     * @param target 目标基本块
     * @return 真分支边
     */
    public static ControlFlowEdge trueBranch(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.TRUE_BRANCH, -1, null);
    }

    /**
     * 创建一条条件为假时的分支边.
     *
     * @param source 源基本块
     * @param target 目标基本块
     * @return 假分支边
     */
    public static ControlFlowEdge falseBranch(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.FALSE_BRANCH, -1, null);
    }

    /**
     * 创建一条异常处理边.
     *
     * @param source    源基本块(受保护的基本块)
     * @param target    目标基本块(异常处理器)
     * @param catchType 捕获的异常类型全限定名
     * @return 异常边
     */
    public static ControlFlowEdge exception(BasicBlock source, BasicBlock target, String catchType) {
        return new ControlFlowEdge(source, target, EdgeKind.EXCEPTION, -1, catchType);
    }

    /**
     * 创建一条返回边,指向控制流图的出口块.
     *
     * @param source 源基本块
     * @param exit   出口基本块
     * @return 返回边
     */
    public static ControlFlowEdge returnEdge(BasicBlock source, BasicBlock exit) {
        return new ControlFlowEdge(source, exit, EdgeKind.RETURN, -1, null);
    }

    /**
     * 创建一条switch默认分支边.
     *
     * @param source 源基本块
     * @param target 目标基本块
     * @return switch默认边
     */
    public static ControlFlowEdge switchDefault(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.SWITCH_DEFAULT, -1, null);
    }

    /**
     * 创建一条抛出边,由athrow指令触发,指向出口块.
     *
     * @param source 源基本块
     * @param exit   出口基本块
     * @return 抛出边
     */
    public static ControlFlowEdge throwEdge(BasicBlock source, BasicBlock exit) {
        return new ControlFlowEdge(source, exit, EdgeKind.THROW, -1, null);
    }
}
