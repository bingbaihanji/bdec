package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InstanceOfExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的 AST 变换器基类,为 {@link RewriteRule} 提供带完整递归遍历的骨架.
 *
 * <p>参考 CFR 的 {@code Op04StructuredStatement.rewrite()} 和
 * Vineflower 的 {@code DirectGraph.iterateExprents()} 设计.
 * 子类只需覆盖相关 transform 方法,无需手写递归样板代码.
 *
 * <p>所有 {@code transform*} 方法默认递归遍历子节点,
 * 子节点变化时重建父节点(不可变更新),否则返回原节点.
 */
public abstract class AstTransformer implements AstVisitor<AstNode, Void> {

    // ==================== Visitor 入口 ====================

    @Override
    public AstNode visitStatement(Statement s, Void ctx) {
        return switch (s.kind()) {
            case BLOCK -> transformBlock((BlockStatement) s);
            case IF -> transformIf((IfStatement) s);
            case LOOP -> transformLoop((LoopStatement) s);
            case SWITCH -> transformSwitch((SwitchStatement) s);
            case TRY -> transformTry((TryStatement) s);
            case RETURN -> transformReturn((ReturnStatement) s);
            case THROW -> transformThrow((ThrowStatement) s);
            case EXPRESSION_STMT -> transformExprStmt((ExpressionStatement) s);
            case VARIABLE_DECL -> transformVarDecl((VariableDeclaration) s);
            case SYNCHRONIZED -> transformSynchronized((SynchronizedStatement) s);
            default -> s;
        };
    }

    @Override
    public AstNode visitExpression(Expression e, Void ctx) {
        return switch (e.kind()) {
            case ASSIGNMENT -> transformAssign((AssignExpr) e);
            case BINARY -> transformBinary((BinExpr) e);
            case UNARY -> transformUnary((UnExpr) e);
            case CONDITIONAL -> transformConditional((CondExpr) e);
            case INVOCATION -> transformInvocation((InvocationExpr) e);
            case FIELD_ACCESS -> transformFieldAccess((FieldAccessExpr) e);
            case ARRAY_ACCESS -> transformArrayAccess((ArrayAccessExpr) e);
            case CAST -> transformCast((CastExpr) e);
            case INSTANCE_OF -> transformInstanceOf((InstanceOfExpr) e);
            case NEW -> transformNew((NewExpr) e);
            case LAMBDA -> transformLambda((LambdaExpr) e);
            case VARIABLE -> transformVarExpr((VarExpr) e);
            default -> e;
        };
    }

    // ==================== 便利方法 ====================

    protected Expression transformExpr(Expression e) {
        AstNode r = visitExpression(e, null);
        return r instanceof Expression expr ? expr : e;
    }

    protected Statement transformStmt(Statement s) {
        AstNode r = visitStatement(s, null);
        return r instanceof Statement stmt ? stmt : s;
    }

    // ==================== 表达式变换(默认递归) ====================

    protected Expression transformAssign(AssignExpr e) {
        Expression t = transformExpr(e.target());
        Expression v = transformExpr(e.value());
        return (t != e.target() || v != e.value())
                ? new AssignExpr(t, v, e.compoundOp()) : e;
    }

    protected Expression transformBinary(BinExpr e) {
        Expression l = transformExpr(e.left());
        Expression r = transformExpr(e.right());
        return (l != e.left() || r != e.right())
                ? new BinExpr(e.operator(), l, r) : e;
    }

    protected Expression transformUnary(UnExpr e) {
        Expression o = transformExpr(e.operand());
        return (o != e.operand()) ? new UnExpr(e.operator(), o) : e;
    }

    protected Expression transformConditional(CondExpr e) {
        Expression c = transformExpr(e.condition());
        Expression t = transformExpr(e.trueExpr());
        Expression f = transformExpr(e.falseExpr());
        return (c != e.condition() || t != e.trueExpr() || f != e.falseExpr())
                ? new CondExpr(c, t, f) : e;
    }

    protected Expression transformInvocation(InvocationExpr e) {
        Expression tgt = e.target() != null ? transformExpr(e.target()) : null;
        List<Expression> args = transformExprList(e.arguments());
        return (tgt != e.target() || args != e.arguments())
                ? new InvocationExpr(tgt, e.methodName(), args, e.returnType()) : e;
    }

    protected Expression transformFieldAccess(FieldAccessExpr e) {
        Expression tgt = e.target() != null ? transformExpr(e.target()) : null;
        return (tgt != e.target()) ? new FieldAccessExpr(tgt, e.fieldName()) : e;
    }

    /** 变量引用叶子节点:默认不变,子类可覆盖以重命名(如 val$x → x). */
    protected Expression transformVarExpr(VarExpr e) {
        return e;
    }

    protected Expression transformArrayAccess(ArrayAccessExpr e) {
        Expression arr = transformExpr(e.array());
        Expression idx = transformExpr(e.index());
        return (arr != e.array() || idx != e.index())
                ? new ArrayAccessExpr(arr, idx) : e;
    }

    protected Expression transformCast(CastExpr e) {
        Expression op = transformExpr(e.operand());
        return (op != e.operand())
                ? new CastExpr(e.targetType(), op, e.typeAnnotations()) : e;
    }

    protected Expression transformInstanceOf(InstanceOfExpr e) {
        Expression op = transformExpr(e.operand());
        return (op != e.operand())
                ? new InstanceOfExpr(op, e.targetType(), e.typeAnnotations()) : e;
    }

    protected Expression transformNew(NewExpr e) {
        List<Expression> args = transformExprList(e.constructorArgs());
        if (args != e.constructorArgs()) {
            return new NewExpr(e.instantiatedType(), e.dimensions(), args,
                    e.anonymousBody(), e.arrayInitializer(), e.typeAnnotations());
        }
        return e;
    }

    protected Expression transformLambda(LambdaExpr e) {
        if (e.isMethodRef()) {
            return e;
        }
        if (e.isExpressionBody() && e.bodyExpr() != null) {
            Expression b = transformExpr(e.bodyExpr());
            return (b != e.bodyExpr())
                    ? LambdaExpr.expression(e.parameters(), b, e.functionalType()) : e;
        } else if (e.bodyBlock() != null) {
            BlockStatement b = (BlockStatement) transformStmt(e.bodyBlock());
            return (b != e.bodyBlock())
                    ? LambdaExpr.block(e.parameters(), b, e.functionalType()) : e;
        }
        return e;
    }

    // ==================== 语句变换(默认递归) ====================

    protected Statement transformBlock(BlockStatement s) {
        List<Statement> stmts = transformStmtList(s.statements());
        return (stmts != s.statements()) ? new BlockStatement(stmts) : s;
    }

    protected Statement transformIf(IfStatement s) {
        Expression cond = transformExpr(s.condition());
        Statement then = transformStmt(s.thenBranch());
        Statement els = s.elseBranch() != null ? transformStmt(s.elseBranch()) : null;
        return (cond != s.condition() || then != s.thenBranch() || els != s.elseBranch())
                ? new IfStatement(cond, then, els) : s;
    }

    protected Statement transformLoop(LoopStatement s) {
        Statement body = transformStmt(s.body());
        return (body != s.body())
                ? new LoopStatement(s.loopKind(), s.condition(), body) : s;
    }

    protected Statement transformSwitch(SwitchStatement s) {
        Expression disc = transformExpr(s.discriminant());
        boolean changed = disc != s.discriminant();
        List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
        for (SwitchStatement.CaseGroup cg : s.cases()) {
            List<Statement> body = transformStmtList(cg.body());
            if (body != cg.body()) {
                changed = true;
            }
            newCases.add(new SwitchStatement.CaseGroup(cg.labels(), body, cg.isDefault()));
        }
        return changed ? new SwitchStatement(disc, newCases, s.isExpression()) : s;
    }

    protected Statement transformTry(TryStatement s) {
        Statement tryBody = transformStmt(s.tryBody());
        boolean changed = tryBody != s.tryBody();
        List<TryStatement.CatchClause> newCatches = new ArrayList<>();
        for (TryStatement.CatchClause cc : s.catchClauses()) {
            Statement body = transformStmt(cc.body());
            if (body != cc.body()) {
                changed = true;
            }
            newCatches.add(new TryStatement.CatchClause(
                    cc.exceptionType(), cc.varName(), body));
        }
        Statement finBody = s.finallyBody() != null
                ? transformStmt(s.finallyBody()) : null;
        if (finBody != s.finallyBody()) {
            changed = true;
        }
        List<TryStatement.Resource> newResources = new ArrayList<>();
        for (TryStatement.Resource r : s.resources()) {
            Expression init = transformExpr(r.init());
            if (init != r.init()) {
                changed = true;
            }
            newResources.add(new TryStatement.Resource(r.type(), r.varName(), init));
        }
        return changed ? new TryStatement(tryBody, newCatches, finBody, newResources) : s;
    }

    protected Statement transformReturn(ReturnStatement s) {
        if (s.value() == null) {
            return s;
        }
        Expression v = transformExpr(s.value());
        return (v != s.value()) ? new ReturnStatement(v) : s;
    }

    protected Statement transformThrow(ThrowStatement s) {
        Expression ex = transformExpr(s.expression());
        return (ex != s.expression()) ? new ThrowStatement(ex) : s;
    }

    protected Statement transformExprStmt(ExpressionStatement s) {
        Expression e = transformExpr(s.expression());
        return (e != s.expression()) ? new ExpressionStatement(e) : s;
    }

    protected Statement transformVarDecl(VariableDeclaration s) {
        if (s.initializer() == null) {
            return s;
        }
        Expression init = transformExpr(s.initializer());
        return (init != s.initializer())
                ? new VariableDeclaration(s.type(), s.name(), init, s.typeAnnotations()) : s;
    }

    protected Statement transformSynchronized(SynchronizedStatement s) {
        Expression mon = transformExpr(s.monitorObject());
        Statement body = transformStmt(s.body());
        return (mon != s.monitorObject() || body != s.body())
                ? new SynchronizedStatement(mon, body) : s;
    }

    // ==================== 列表遍历 ====================

    protected List<Expression> transformExprList(List<Expression> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        List<Expression> r = new ArrayList<>(list.size());
        boolean ch = false;
        for (Expression e : list) {
            Expression t = transformExpr(e);
            r.add(t);
            if (t != e) {
                ch = true;
            }
        }
        return ch ? r : list;
    }

    protected List<Statement> transformStmtList(List<Statement> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        List<Statement> r = new ArrayList<>(list.size());
        boolean ch = false;
        for (Statement s : list) {
            Statement t = transformStmt(s);
            if (t != s) {
                ch = true;
            }
            if (t != null) {
                r.add(t);
            } else {
                ch = true;
            }
        }
        return ch ? r : list;
    }

    /** 便捷:变换整个方法体 */
    protected Statement transformMethodBody(Statement body) {
        return body != null ? transformStmt(body) : null;
    }
}
