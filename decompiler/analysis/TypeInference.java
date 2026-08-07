package com.bingbaihanji.bdec.decompiler.analysis;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.ir.MethodIr;

public interface TypeInference {

    MethodIr infer(MethodIr ir, DecompileContext context);
}
