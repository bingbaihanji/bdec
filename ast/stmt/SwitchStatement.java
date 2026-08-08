package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/** Represents a switch statement or expression (Java 14+). */
public final class SwitchStatement extends Statement {

    private final Expression discriminant;

    private final List<CaseGroup> cases;

    private final boolean isExpression;

    public SwitchStatement(Expression discriminant, List<CaseGroup> cases) {
        this(discriminant, cases, false);
    }

    public SwitchStatement(Expression discriminant, List<CaseGroup> cases, boolean isExpression) {
        this.discriminant = discriminant;
        this.cases = List.copyOf(cases);
        this.isExpression = isExpression;
    }

    public Expression discriminant() {return discriminant;}

    public List<CaseGroup> cases() {return cases;}

    public boolean isExpression() {return isExpression;}

    @Override
    public AstKind kind() {return AstKind.SWITCH;}

    @Override
    public List<AstNode> children() {
        List<AstNode> kids = new java.util.ArrayList<>();
        kids.add(discriminant);
        for (CaseGroup cg : cases) {
            kids.addAll(cg.body());
        }
        return kids;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}

    /** A single case in a switch: optional match labels + body statements. */
    public record CaseGroup(List<Expression> labels, List<Statement> body, boolean isDefault) {

        public CaseGroup {
            labels = labels != null ? List.copyOf(labels) : List.of();
            body = List.copyOf(body);
        }
    }
}
