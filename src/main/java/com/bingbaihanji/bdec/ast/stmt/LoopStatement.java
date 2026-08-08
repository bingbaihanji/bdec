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

    /** For-each: the variable declared in the loop header ({@code Type var}).
     *  Stored as an AssignExpr or VarExpr; emits as {@code Type var} in for-each. */
    private final Expression forEachVar;

    /** Full constructor for for-loops. */
    public LoopStatement(LoopKind k, Expression init, Expression cond, Expression incr, Statement b) {
        loopKind = k;
        initExpr = init;
        condition = cond;
        incrExpr = incr;
        body = b;
        forEachVar = null;
    }

    /** Constructor for for-each loops.
     *  @param k         must be {@link LoopKind#FOR_EACH}
     *  @param varExpr   the loop variable expression (VarExpr or AssignExpr)
     *  @param iterable  the collection/array to iterate over
     *  @param b         the loop body
     */
    public LoopStatement(LoopKind k, Expression varExpr, Expression iterable, Statement b) {
        loopKind = k;
        initExpr = null;
        condition = iterable;  // iterable expression stored as condition
        incrExpr = null;
        body = b;
        forEachVar = varExpr;
    }

    /** Backward-compatible constructor without init/incr. */
    public LoopStatement(LoopKind k, Expression c, Statement b) {
        this(k, null, c, null, b);
    }

    public LoopKind loopKind() {return loopKind;}

    /** For-loop initializer expression, or for-each variable. */
    public Expression initExpr() {return initExpr;}

    public Expression condition() {return condition;}

    /** For-loop increment expression, or null. */
    public Expression incrExpr() {return incrExpr;}

    /** For-each loop variable, or null for regular for/while loops. */
    public Expression forEachVar() {return forEachVar;}

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
