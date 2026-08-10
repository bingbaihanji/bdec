package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/**
 * synchronized 语句节点,表示 Java 中的 {@code synchronized} 同步代码块.
 *
 * <p>包含监视器对象表达式和同步块内的语句体.
 * 对应 Java 语法中的 {@code synchronized (lock) { ... }} 结构.
 */
public final class SynchronizedStatement extends Statement {

    /** 监视器对象表达式(即 synchronized 括号内的锁对象) */
    private final Expression monitorObject;

    /** 同步块内的语句体 */
    private final Statement body;

    /**
     * 构造一个 synchronized 语句.
     *
     * @param monitorObject 监视器对象表达式
     * @param body          同步块语句体
     */
    public SynchronizedStatement(Expression monitorObject, Statement body) {
        this.monitorObject = monitorObject;
        this.body = body;
    }

    /** @return 监视器对象表达式 */
    public Expression monitorObject() {return monitorObject;}

    /** @return 同步块语句体 */
    public Statement body() {return body;}

    @Override
    public AstKind kind() {return AstKind.SYNCHRONIZED;}

    @Override
    public List<AstNode> children() {
        return List.of(monitorObject, body);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitStatement(this, context);
    }
}
