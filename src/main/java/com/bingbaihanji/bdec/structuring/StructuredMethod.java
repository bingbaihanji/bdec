package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.ir.LinearIr;

public record StructuredMethod(
        MethodModel method,
        LinearIr ir,
        BlockStatement body
) {}
