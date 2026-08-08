package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.ArrayList;
import java.util.List;

public final class LoopStatement extends Statement {

    private final LoopKind loopKind;

    private final Expression initExpr;

    private final Expression condition;

    private final Expression incrExpr;

    private final Statement body;

    /** Full constructor for for-loops. */
    public LoopStatement(LoopKind k, Expression init, Expression cond, Expression incr, Statement b) {
        loopKind = k;
        initExpr = init;
        condition = cond;
        incrExpr = incr;
        body = b;
    }

    /** Backward-compatible constructor without init/incr. */
    public LoopStatement(LoopKind k, Expression c, Statement b) {
        this(k, null, c, null, b);
    }

    public LoopKind loopKind() {return loopKind;}

    /** For-loop initializer expression, or null. */
    public Expression initExpr() {return initExpr;}

    public Expression condition() {return condition;}

    /** For-loop increment expression, or null. */
    public Expression incrExpr() {return incrExpr;}

    public Statement body() {return body;}

    @Override
    public AstKind kind() {return AstKind.LOOP;}

    @Override
    public List<AstNode> children() {
        List<AstNode> kids = new ArrayList<>();
        if (initExpr != null) {
            kids.add(initExpr);
        }
        if (condition != null) {
            kids.add(condition);
        }
        if (incrExpr != null) {
            kids.add(incrExpr);
        }
        kids.add(body);
        return kids;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    public enum LoopKind {
        WHILE,
        DO_WHILE,
        FOR,
        FOR_EACH
    }
}
