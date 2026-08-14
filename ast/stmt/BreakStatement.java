package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * break 语句.
 *
 * <p>由 BlockReducer 在构建 switch 语句时生成:case 体在字节码中以
 * goto 跳出 switch(对应源码中的 break),翻译后需要显式补上 break
 * 以保持"不贯穿"的语义.</p>
 */
public final class BreakStatement extends Statement {

    @Override
    public AstKind kind() {return AstKind.BREAK;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    @Override
    public String toString() {return "break;";}
}
