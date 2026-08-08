package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/** Represents a try-catch-finally statement. */
public final class TryStatement extends Statement {

    private final Statement tryBody;

    private final List<CatchClause> catchClauses;

    private final Statement finallyBody;

    public TryStatement(Statement tryBody, List<CatchClause> catchClauses, Statement finallyBody) {
        this.tryBody = tryBody;
        this.catchClauses = catchClauses != null ? List.copyOf(catchClauses) : List.of();
        this.finallyBody = finallyBody;
    }

    public Statement tryBody() {return tryBody;}

    public List<CatchClause> catchClauses() {return catchClauses;}

    public Statement finallyBody() {return finallyBody;}

    @Override
    public AstKind kind() {return AstKind.TRY;}

    @Override
    public List<AstNode> children() {
        List<AstNode> kids = new java.util.ArrayList<>();
        kids.add(tryBody);
        for (CatchClause cc : catchClauses) {
            kids.add(cc.body());
        }
        if (finallyBody != null) {
            kids.add(finallyBody);
        }
        return kids;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    /** A single catch clause: exception type, variable name, body. */
    public record CatchClause(String exceptionType, String varName, Statement body) {}
}
