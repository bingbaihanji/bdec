package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * 循环语句节点,表示 while,do-while,for 和增强 for-each 四种循环结构.
 *
 * <p>根据 {@link LoopKind} 区分循环类型.对于 for-each 循环,
 * 使用特殊的构造函数来设置循环变量和可迭代表达式.
 */
public final class LoopStatement extends Statement {

    /** 循环类型枚举,区分 while / do-while / for / for-each */
    private final LoopKind loopKind;

    /** for 循环的初始化表达式(仅 for 循环使用) */
    private final Expression initExpr;

    /** 循环条件表达式 */
    private final Expression condition;

    /** for 循环的增量表达式(仅 for 循环使用) */
    private final Expression incrExpr;

    /** 循环体语句 */
    private final Statement body;

    /**
     * for-each 循环中的变量声明表达式(如 {@code Type var}).
     * 内部存储为 AssignExpr 或 VarExpr,在输出时渲染为 {@code Type var}.
     */
    private final Expression forEachVar;

    /** for-each 元素变量的类型(可为 null,发射时回退为 Object) */
    private final JavaType forEachVarType;

    /**
     * for 循环的完整构造器.
     *
     * @param k    循环类型
     * @param init 初始化表达式
     * @param cond 条件表达式
     * @param incr 增量表达式
     * @param b    循环体语句
     */
    /** 完整构造器(含 for-each 元素变量与类型) */
    public LoopStatement(LoopKind k, Expression init, Expression cond, Expression incr,
                         Statement b, Expression forEachVar, JavaType forEachVarType) {
        this.loopKind = k;
        this.initExpr = init;
        this.condition = cond;
        this.incrExpr = incr;
        this.body = b;
        this.forEachVar = forEachVar;
        this.forEachVarType = forEachVarType;
    }

    public LoopStatement(LoopKind k, Expression init, Expression cond, Expression incr, Statement b) {
        loopKind = k;
        initExpr = init;
        condition = cond;
        incrExpr = incr;
        body = b;
        forEachVar = null;
        forEachVarType = null;
    }

    /**
     * for-each 循环的专用构造器.
     *
     * @param k        循环类型,必须为 {@link LoopKind#FOR_EACH}
     * @param varExpr  循环变量表达式(VarExpr 或 AssignExpr)
     * @param iterable 待遍历的集合或数组表达式
     * @param b        循环体语句
     */
    public LoopStatement(LoopKind k, Expression varExpr, Expression iterable, Statement b) {
        this(k, varExpr, iterable, b, null);
    }

    /** FOR_EACH 构造器(含元素变量类型) */
    public LoopStatement(LoopKind k, Expression varExpr, Expression iterable, Statement b,
                         JavaType varType) {
        loopKind = k;
        initExpr = null;
        condition = iterable;  // 将可迭代表达式存储在 condition 字段中
        incrExpr = null;
        body = b;
        forEachVar = varExpr;
        forEachVarType = varType;
    }

    /**
     * 向后兼容的构造器,不含初始化和增量表达式.
     *
     * @param k 循环类型
     * @param c 条件表达式
     * @param b 循环体语句
     */
    public LoopStatement(LoopKind k, Expression c, Statement b) {
        this(k, null, c, null, b);
    }

    /** @return 循环类型 */
    public LoopKind loopKind() {return loopKind;}

    /** @return for 循环初始化表达式或 for-each 变量表达式 */
    public Expression initExpr() {return initExpr;}

    /** @return 循环条件表达式 */
    public Expression condition() {return condition;}

    /** @return for 循环增量表达式,非 for 循环时为 null */
    public Expression incrExpr() {return incrExpr;}

    /** @return for-each 循环变量表达式,普通 for/while 循环时为 null */
    public Expression forEachVar() {return forEachVar;}

    /** @return for-each 元素变量的类型(可为 null) */
    public JavaType forEachVarType() {return forEachVarType;}

    /** 返回带元素类型的 for-each 循环副本 */
    public LoopStatement withForEachVarType(JavaType t) {
        return new LoopStatement(loopKind, initExpr, condition, incrExpr, body,
                forEachVar, t);
    }

    /** @return 循环体语句 */
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

    /** 循环类型枚举 */
    public enum LoopKind {
        /** while 循环 */
        WHILE,
        /** do-while 循环 */
        DO_WHILE,
        /** 传统 for 循环 */
        FOR,
        /** 增强 for-each 循环 */
        FOR_EACH
    }
}
