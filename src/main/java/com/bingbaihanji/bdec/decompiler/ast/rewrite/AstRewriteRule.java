package com.bingbaihanji.bdec.decompiler.ast.rewrite;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.ast.AstNode;

public interface AstRewriteRule {

    String name();

    boolean matches(AstNode node, DecompileContext context);

    AstNode rewrite(AstNode node, DecompileContext context);
}
