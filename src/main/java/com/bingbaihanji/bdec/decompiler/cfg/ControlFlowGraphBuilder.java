package com.bingbaihanji.bdec.decompiler.cfg;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.bytecode.MethodModel;

public interface ControlFlowGraphBuilder {

    ControlFlowGraph build(MethodModel method, DecompileContext context);
}
