package com.bingbaihanji.bdec.decompiler.ast;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.bytecode.ClassFileModel;
import com.bingbaihanji.bdec.decompiler.structuring.StructuredMethod;

import java.util.List;

public interface AstBuilder {

    CompilationUnit build(ClassFileModel classFile, List<StructuredMethod> methods, DecompileContext context);
}
