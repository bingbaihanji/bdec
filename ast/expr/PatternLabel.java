package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 模式匹配 switch 的 case 标签,表示 {@code case null} 或
 * {@code case Type var when guard} 形式.
 *
 * <p>该节点仅作为 {@code SwitchStatement.CaseGroup} 的标签出现,由
 * {@code StatementEmitter.emitSwitch} 特判渲染,不经过通用表达式发射器.</p>
 */
public final class PatternLabel extends Expression {

    /** 匹配类型简单名(如 {@code "Integer"}),null case 时为 {@code null} */
    private final String typeName;

    /** 模式变量名(如 {@code "i"}),无模式变量时为 {@code null} */
    private final String varName;

    /** 守卫条件({@code when} 之后的表达式),无守卫时为 {@code null} */
    private final Expression guard;

    /** 是否为 {@code case null} 标签 */
    private final boolean nullCase;

    /**
     * 构造模式标签.
     *
     * @param typeName 匹配类型简单名,null case 时为 {@code null}
     * @param varName  模式变量名,可为 {@code null}
     * @param guard    守卫条件,可为 {@code null}
     * @param nullCase 是否为 {@code case null}
     */
    public PatternLabel(String typeName, String varName, Expression guard, boolean nullCase) {
        this.typeName = typeName;
        this.varName = varName;
        this.guard = guard;
        this.nullCase = nullCase;
    }

    /** 构造 {@code case null} 标签. */
    public static PatternLabel nullLabel() {
        return new PatternLabel(null, null, null, true);
    }

    /** 构造 {@code case Type var} 标签(可带守卫). */
    public static PatternLabel type(String typeName, String varName, Expression guard) {
        return new PatternLabel(typeName, varName, guard, false);
    }

    /** @return 匹配类型简单名,null case 时为 {@code null} */
    public String typeName() {return typeName;}

    /** @return 模式变量名,无模式变量时为 {@code null} */
    public String varName() {return varName;}

    /** @return 守卫条件,无守卫时为 {@code null} */
    public Expression guard() {return guard;}

    /** @return 是否为 {@code case null} 标签 */
    public boolean nullCase() {return nullCase;}

    @Override
    public AstKind kind() {return AstKind.PATTERN_LABEL;}

    @Override
    public List<AstNode> children() {
        return guard != null ? List.of(guard) : List.of();
    }

    @Override
    public int precedence() {return 0;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
