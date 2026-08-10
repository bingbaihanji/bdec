package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;

import java.util.List;

/**
 * switch 语句/表达式节点,表示 Java 中的 switch 结构.
 *
 * <p>支持传统 switch 语句和 Java 14+ 引入的 switch 表达式(通过 {@link #isExpression} 区分).
 * 每个 case 分支使用 {@link CaseGroup} record 表示.
 */
public final class SwitchStatement extends Statement {

    /** switch 的判别式(被匹配的表达式) */
    private final Expression discriminant;

    /** case 分支分组列表 */
    private final List<CaseGroup> cases;

    /** 是否为 switch 表达式(Java 14+) */
    private final boolean isExpression;

    /**
     * 构造一个 switch 语句(非表达式).
     *
     * @param discriminant 判别式表达式
     * @param cases        case 分支列表
     */
    public SwitchStatement(Expression discriminant, List<CaseGroup> cases) {
        this(discriminant, cases, false);
    }

    /**
     * 构造一个 switch 语句或表达式.
     *
     * @param discriminant 判别式表达式
     * @param cases        case 分支列表
     * @param isExpression 是否为 switch 表达式
     */
    public SwitchStatement(Expression discriminant, List<CaseGroup> cases, boolean isExpression) {
        this.discriminant = discriminant;
        this.cases = List.copyOf(cases);
        this.isExpression = isExpression;
    }

    /** @return 判别式表达式 */
    public Expression discriminant() {return discriminant;}

    /** @return case 分支列表(不可变) */
    public List<CaseGroup> cases() {return cases;}

    /** @return {@code true} 表示 switch 表达式,{@code false} 表示 switch 语句 */
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

    /**
     * 单个 case 分支组:可选的匹配标签列表 + 分支体语句列表.
     */
    public record CaseGroup(List<Expression> labels, List<Statement> body, boolean isDefault) {

        public CaseGroup {
            labels = labels != null ? List.copyOf(labels) : List.of();
            body = List.copyOf(body);
        }
    }
}
