package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/**
 * 条件分支语句节点,表示 if-else 控制流结构.
 *
 * <p>包含条件表达式,then 分支和可选的 else 分支.
 * 当 elseBranch 为 null 时表示仅有 if 分支.
 */
public final class IfStatement extends Statement {

    /** 条件表达式 */
    private final Expression condition;

    /** 条件为真时执行的 then 分支 */
    private final Statement thenBranch;

    /** 条件为假时执行的 else 分支,可为 null */
    private final Statement elseBranch;

    /**
     * 构造一个条件分支语句.
     *
     * @param c 条件表达式
     * @param t then 分支语句
     * @param e else 分支语句,可为 null
     */
    public IfStatement(Expression c, Statement t, Statement e) {
        condition = c;
        thenBranch = t;
        elseBranch = e;
    }

    /** @return 条件表达式 */
    public Expression condition() {return condition;}

    /** @return then 分支语句 */
    public Statement thenBranch() {return thenBranch;}

    /** @return else 分支语句,可为 null */
    public Statement elseBranch() {return elseBranch;}

    @Override
    public AstKind kind() {return AstKind.IF;}

    @Override
    public List<AstNode> children() {
        return elseBranch != null ? List.of(condition, thenBranch, elseBranch) : List.of(condition, thenBranch);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
