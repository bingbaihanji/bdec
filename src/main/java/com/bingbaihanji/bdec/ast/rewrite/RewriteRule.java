package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.CompilationUnit;

public interface RewriteRule {

    String name();

    default String description() {return "";}

    CompilationUnit rewrite(CompilationUnit unit, DecompileContext context);
}
