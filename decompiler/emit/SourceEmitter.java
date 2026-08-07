package com.bingbaihanji.bdec.decompiler.emit;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.ast.CompilationUnit;

public interface SourceEmitter {

    SourceFile emit(CompilationUnit unit, DecompileContext context);
}
