package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * continue 语句.
 *
 * <p>由 BlockReducer 在结构化含内部分支的循环体时生成:
 * 体内到 latch 的跳转对应源码中的 continue.</p>
 */
public final class ContinueStatement extends Statement {

    @Override
    public AstKind kind() {return AstKind.CONTINUE;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    @Override
    public String toString() {return "continue;";}
}
