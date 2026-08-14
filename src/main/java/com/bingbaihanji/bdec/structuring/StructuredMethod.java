package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.util.List;
import java.util.Map;

/**
 * 结构化方法的结果记录.
 *
 * <p>包含原始方法模型,线性 IR,归约后的 AST 方法体,
 * 以及各类结构检测注解(循环,条件分支,switch,try-catch).
 *
 * @param method              原始方法模型
 * @param ir                  线性 IR
 * @param body                归约后的 AST 方法体
 * @param loopAnnotations     循环注解(基本块 → LoopInfo)
 * @param ifAnnotations       if 注解(基本块 → IfInfo)
 * @param switchAnnotations   switch 注解(基本块 → SwitchInfo)
 * @param tryCatchAnnotations try-catch 注解列表(同一处理器可对应多个异常范围)
 */
public record StructuredMethod(
        MethodModel method,
        LinearIr ir,
        BlockStatement body,
        Map<BasicBlock, LoopInfo> loopAnnotations,
        Map<BasicBlock, IfInfo> ifAnnotations,
        Map<BasicBlock, SwitchInfo> switchAnnotations,
        List<TryCatchInfo> tryCatchAnnotations
) {

    /** 仅含循环和 if 注解的简化构造 */
    public StructuredMethod(MethodModel method, LinearIr ir, BlockStatement body,
                            Map<BasicBlock, LoopInfo> loopAnns,
                            Map<BasicBlock, IfInfo> ifAnns) {
        this(method, ir, body, loopAnns, ifAnns, Map.of(), List.of());
    }

    /** 不含任何注解的最简构造 */
    public StructuredMethod(MethodModel method, LinearIr ir, BlockStatement body) {
        this(method, ir, body, Map.of(), Map.of(), Map.of(), List.of());
    }

    /** 判断基本块是否为循环头 */
    public boolean isLoopHeader(BasicBlock b) {return loopAnnotations.containsKey(b);}

    /** 判断基本块是否为 if 条件头 */
    public boolean isIfHeader(BasicBlock b) {return ifAnnotations.containsKey(b);}

    /** 判断基本块是否为 switch 头 */
    public boolean isSwitchHeader(BasicBlock b) {return switchAnnotations.containsKey(b);}

    /** 判断基本块是否属于某个 try 块 */
    public boolean isTryBlock(BasicBlock b) {
        return tryCatchAnnotations.stream().anyMatch(t -> t.tryBlocks().contains(b));
    }
}
