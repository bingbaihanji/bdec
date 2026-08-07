package com.bingbaihanji.bdec.cfg;

public record ControlFlowEdge(
        BasicBlock source,
        BasicBlock target,
        EdgeKind kind,
        int switchKey,
        String catchType
) {

    public static ControlFlowEdge fallThrough(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.FALL_THROUGH, -1, null);
    }

    public static ControlFlowEdge gotoEdge(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.GOTO, -1, null);
    }

    public static ControlFlowEdge trueBranch(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.TRUE_BRANCH, -1, null);
    }

    public static ControlFlowEdge falseBranch(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.FALSE_BRANCH, -1, null);
    }

    public static ControlFlowEdge exception(BasicBlock source, BasicBlock target, String catchType) {
        return new ControlFlowEdge(source, target, EdgeKind.EXCEPTION, -1, catchType);
    }

    public static ControlFlowEdge returnEdge(BasicBlock source, BasicBlock exit) {
        return new ControlFlowEdge(source, exit, EdgeKind.RETURN, -1, null);
    }

    public static ControlFlowEdge throwEdge(BasicBlock source, BasicBlock exit) {
        return new ControlFlowEdge(source, exit, EdgeKind.THROW, -1, null);
    }
}
