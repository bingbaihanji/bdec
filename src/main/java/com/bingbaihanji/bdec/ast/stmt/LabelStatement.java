package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 标签声明语句:{@code <label>:}.
 *
 * <p>与 {@link GotoStatement} 配套,构成不可归约控制流的扁平输出
 * (参照 Procyon).标签后紧跟该基本块的语句.</p>
 */
public final class LabelStatement extends Statement {

    private final String label;

    public LabelStatement(String label) {
        this.label = label;
    }

    /** @return 标签名 */
    public String label() {return label;}

    @Override
    public AstKind kind() {return AstKind.LABEL;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    @Override
    public String toString() {return label + ":";}
}
