package com.bingbaihanji.bdec.decompiler.ast.rewrite;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.ast.CompilationUnit;

import java.util.List;

public interface AstRewriter {

    CompilationUnit rewrite(CompilationUnit unit, List<AstRewriteRule> rules, DecompileContext context);
}
