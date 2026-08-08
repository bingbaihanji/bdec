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
        Map<BasicBlock, IfInfo> ifAnnotations
) {

    public StructuredMethod(MethodModel method, LinearIr ir, BlockStatement body) {
        this(method, ir, body, Map.of(), Map.of());
    }

    public boolean isLoopHeader(BasicBlock b) {return loopAnnotations.containsKey(b);}

    public boolean isIfHeader(BasicBlock b) {return ifAnnotations.containsKey(b);}
}
