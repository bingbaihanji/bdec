package com.bingbaihanji.bdec.decompiler.structuring;

import com.bingbaihanji.bdec.decompiler.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.decompiler.bytecode.MethodModel;
import com.bingbaihanji.bdec.decompiler.ir.MethodIr;

public interface StructuredMethod {

    MethodModel method();

    MethodIr ir();

    BlockStatement body();
}
