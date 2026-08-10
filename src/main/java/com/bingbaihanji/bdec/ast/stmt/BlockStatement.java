package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 代码块语句节点,表示由一对花括号包围的若干语句序列.
 *
 * <p>对应 Java 中的 {@code { ... }} 代码块,是作用域和语句组合的基本单元.
 */
public final class BlockStatement extends Statement {

    /** 代码块内包含的语句列表 */
    private final List<Statement> statements;

    /**
     * 构造一个代码块语句.
     *
     * @param statements 包含的语句列表
     */
    public BlockStatement(List<Statement> statements) {this.statements = List.copyOf(statements);}

    /** @return 代码块内包含的语句列表(不可变) */
    public List<Statement> statements() {return statements;}

    @Override
    public AstKind kind() {return AstKind.BLOCK;}

    @Override
    public List<AstNode> children() {return List.copyOf(statements);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
