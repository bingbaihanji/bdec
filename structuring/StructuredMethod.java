package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.util.Map;

public record StructuredMethod(
        MethodModel method,
        LinearIr ir,
        BlockStatement body,
        Map<BasicBlock, LoopInfo> loopAnnotations,
        Map<BasicBlock, IfInfo> ifAnnotations,
        Map<BasicBlock, SwitchInfo> switchAnnotations,
        Map<BasicBlock, TryCatchInfo> tryCatchAnnotations
) {

    public StructuredMethod(MethodModel method, LinearIr ir, BlockStatement body,
                            Map<BasicBlock, LoopInfo> loopAnns,
                            Map<BasicBlock, IfInfo> ifAnns) {
        this(method, ir, body, loopAnns, ifAnns, Map.of(), Map.of());
    }

    public StructuredMethod(MethodModel method, LinearIr ir, BlockStatement body) {
        this(method, ir, body, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public boolean isLoopHeader(BasicBlock b) {return loopAnnotations.containsKey(b);}

    public boolean isIfHeader(BasicBlock b) {return ifAnnotations.containsKey(b);}

    public boolean isSwitchHeader(BasicBlock b) {return switchAnnotations.containsKey(b);}

    public boolean isTryBlock(BasicBlock b) {return tryCatchAnnotations.containsKey(b);}
}
