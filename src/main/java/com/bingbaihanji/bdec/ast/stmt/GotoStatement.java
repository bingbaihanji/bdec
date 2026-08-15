package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 带标签跳转语句:{@code goto <label>;}.
 *
 * <p>不可归约控制流的语义安全网(参照 Procyon):当 CFG 无法结构化为
 * if/loop/switch 时,以扁平 + 标签 + goto 的方式输出,保证语义正确
 * (虽然结构不美观).由 {@code IrreducibleHandler} 的扁平回退生成.</p>
 */
public final class GotoStatement extends Statement {

    private final String label;

    public GotoStatement(String label) {
        this.label = label;
    }

    /** @return 跳转目标标签名 */
    public String label() {return label;}

    @Override
    public AstKind kind() {return AstKind.GOTO;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    @Override
    public String toString() {return "goto " + label + ";";}
}
