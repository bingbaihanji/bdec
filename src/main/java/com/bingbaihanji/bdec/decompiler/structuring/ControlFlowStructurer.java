package com.bingbaihanji.bdec.decompiler.structuring;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.ir.MethodIr;

public interface ControlFlowStructurer {

    StructuredMethod structure(MethodIr ir, DecompileContext context);
}
