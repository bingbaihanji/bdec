package com.bingbaihanji.bdec.decompiler.pipeline;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.DecompileResult;

public interface DecompilerPipeline {

    DecompileResult decompile(String internalName, byte[] classBytes, DecompileContext context);
}
