package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * try-catch-finally 语句节点,表示 Java 中的异常处理结构.
 *
 * <p>包含 try 体,catch 子句列表和可选的 finally 体.
 * 每个 catch 子句由 {@link CatchClause} record 表示.
 */
public final class TryStatement extends Statement {

    /** try 块体语句 */
    private final Statement tryBody;

    /** catch 子句列表 */
    private final List<CatchClause> catchClauses;

    /** finally 块体语句,可为 null 表示无 finally 块 */
    private final Statement finallyBody;

    /** try-with-resources 资源声明列表(可为空) */
    private final List<Resource> resources;

    /**
     * 构造一个 try 语句(无资源).
     *
     * @param tryBody       try 块体
     * @param catchClauses  catch 子句列表
     * @param finallyBody   finally 块体,可为 null
     */
    public TryStatement(Statement tryBody, List<CatchClause> catchClauses, Statement finallyBody) {
        this(tryBody, catchClauses, finallyBody, List.of());
    }

    /**
     * 构造一个 try 语句(含 try-with-resources 资源).
     *
     * @param tryBody       try 块体
     * @param catchClauses  catch 子句列表
     * @param finallyBody   finally 块体,可为 null
     * @param resources     资源声明列表
     */
    public TryStatement(Statement tryBody, List<CatchClause> catchClauses, Statement finallyBody,
                        List<Resource> resources) {
        this.tryBody = tryBody;
        this.catchClauses = catchClauses != null ? List.copyOf(catchClauses) : List.of();
        this.finallyBody = finallyBody;
        this.resources = resources != null ? List.copyOf(resources) : List.of();
    }

    /** @return try 块体语句 */
    public Statement tryBody() {return tryBody;}

    /** @return catch 子句列表(不可变) */
    public List<CatchClause> catchClauses() {return catchClauses;}

    /** @return finally 块体语句,可为 null */
    public Statement finallyBody() {return finallyBody;}

    /** @return try-with-resources 资源声明列表(不可变) */
    public List<Resource> resources() {return resources;}

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

    /**
     * 单个 catch 子句:异常类型名称,变量名称和子句体.
     */
    public record CatchClause(String exceptionType, String varName, Statement body) {}

    /**
     * try-with-resources 的单个资源声明:{@code Type varName = init}.
     */
    public record Resource(JavaType type, String varName, Expression init) {}
}
