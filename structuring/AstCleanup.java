package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.type.JavaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AST 清理与终结判断工具集(从 BlockReducer 抽取).
 *
 * <p>集中存放对已构建 AST 的后处理纯函数:
 * synchronized 前导剥离与识别、泄漏 catch 语句清理、
 * 重复 finally 去重、switch case 终结判断、孤儿语句包装等.
 * 全部为无状态 static 方法,便于独立测试与复用.</p>
 */
final class AstCleanup {

    private AstCleanup() {}

/** 语句是否为简单终止语句(单条 throw/return 或仅含一条的块) */
    static boolean isSimpleTerminal(Statement s) {
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement
                || s instanceof com.bingbaihanji.bdec.ast.stmt.ThrowStatement) {
            return true;
        }
        if (s instanceof BlockStatement bs && bs.statements().size() == 1) {
            return isSimpleTerminal(bs.statements().getFirst());
        }
        return false;
    }

/** 语句的粗略语句数量(块内语句数,非块为 1) */
    static int countStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            int n = 0;
            for (Statement c : bs.statements()) {
                n += countStatements(c);
            }
            return n;
        }
        if (s instanceof IfStatement i) {
            return 1 + countStatements(i.thenBranch())
                    + (i.elseBranch() != null ? countStatements(i.elseBranch()) : 0);
        }
        return 1;
    }

/** 条件取反并简化 */
    static Expression negateCond(Expression cond) {
        if (cond == null) {
            return new com.bingbaihanji.bdec.ast.expr.LitExpr(true, JavaType.BOOLEAN);
        }
        return simplifyCondition(new UnExpr(UnaryOperator.NOT, cond));
    }

/** 检查 case 体在字节码中是否以 goto 跳出 switch(对应源码中的 break).
     *  <p>case 体内任一块存在指向 switch 外(非 case 体、非出口块)的 GOTO 边,
     *  说明源码中该 case 以 break 结束.若为 FALL_THROUGH 落入下一个 case
     *  或自然流出 switch 末尾,则无需 break.</p> */
    static boolean caseEndsWithBreak(Set<BasicBlock> caseBlocks,
                                      Set<BasicBlock> allCaseBlocks,
                                      ControlFlowGraph graph,
                                      BasicBlock restartBlock) {
        for (BasicBlock b : caseBlocks) {
            for (var e : graph.outgoingOf(b)) {
                if (e.kind() == EdgeKind.GOTO
                        && !allCaseBlocks.contains(e.target())
                        && e.target() != graph.exitBlock()
                        && e.target() != restartBlock) {
                    return true;
                }
            }
        }
        return false;
    }

/** 检查语句列表是否以终止语句(return/throw/break/continue)结尾 */
    static boolean endsWithTerminator(List<Statement> body) {
        if (body.isEmpty()) {
            return false;
        }
        Statement last = body.getLast();
        if (last instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement
                || last instanceof com.bingbaihanji.bdec.ast.stmt.ThrowStatement
                || last instanceof com.bingbaihanji.bdec.ast.stmt.BreakStatement
                || last.kind() == com.bingbaihanji.bdec.ast.AstKind.CONTINUE) {
            return true;
        }
        // if-else 的两个分支都终止(如守卫翻译的 if(...) { x; break; } else { y; break; })
        if (last instanceof IfStatement i && i.elseBranch() != null) {
            return endsWithTerminatorStmt(i.thenBranch())
                    && endsWithTerminatorStmt(i.elseBranch());
        }
        return false;
    }

/** 单语句是否为终止语句 */
    static boolean endsWithTerminatorStmt(Statement s) {
        if (s == null) {
            return false;
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement
                || s instanceof com.bingbaihanji.bdec.ast.stmt.ThrowStatement
                || s instanceof com.bingbaihanji.bdec.ast.stmt.BreakStatement
                || s.kind() == com.bingbaihanji.bdec.ast.AstKind.CONTINUE) {
            return true;
        }
        if (s instanceof BlockStatement bs) {
            return endsWithTerminator(bs.statements());
        }
        if (s instanceof IfStatement i && i.elseBranch() != null) {
            return endsWithTerminatorStmt(i.thenBranch())
                    && endsWithTerminatorStmt(i.elseBranch());
        }
        return false;
    }

/** 将 case 体中的孤立表达式包装为 return(非 void 方法).
     *  字符串拼接等表达式作为孤立语句在 switch case 中是无效 Java.
     *  包装后,其后的语句均不可达,全部截断. */
    static List<Statement> wrapOrphansAsReturns(List<Statement> body) {
        List<Statement> result = new ArrayList<>(body);
        for (int i = result.size() - 1; i >= 0; i--) {
            Statement s = result.get(i);
            if (s instanceof ExpressionStatement es
                    && es.expression() != null
                    && !StatementUtils.isIgnorableExpr(es.expression())
                    && !StatementUtils.isVoidExpr(es.expression())
                    && !StatementUtils.isAssignExpr(es.expression())) {
                result.set(i, new ReturnStatement(es.expression()));
                // 截断其后的所有语句(return 后不可达)
                if (i + 1 < result.size()) {
                    result = new ArrayList<>(result.subList(0, i + 1));
                }
                break;
            }
        }
        return result;
    }

/**
     * 如果指令具有 BOOLEAN_RETURN 注解且表达式为数值 LitExpr,
     * 则将其替换为布尔 LitExpr.
     */
    static Expression applyBooleanAnnotation(IrInstruction insn, Expression expr) {
        if (!insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN)) {
            return expr;
        }
        // 如果值来自 PHI,不覆盖——分支上下文解析已经选择了正确的逐分支值
        if (!insn.operands().isEmpty()
                && insn.operands().getFirst() instanceof InstructionRef ref
                && ref.instruction().opcode() == IrOpcode.PHI) {
            return expr;
        }
        var ann = insn.getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN);
        if (ann == null) {
            return expr;
        }
        boolean boolVal = ann.getBoolean(
                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_BOOLEAN_VALUE);
        return new LitExpr(boolVal, JavaType.BOOLEAN);
    }

/** 检查语句树是否包含 synchronized 块注解.
     *  通过 IR 级别标记检测的回退方案. */
    static boolean isSynchronizedBlock(Statement stmt) {
        return false; // 检测现在使用 groupHasSynchronizedAnnotation 中的 IR 级别标记
    }

/** 递归检查语句树中是否包含 SynchronizedStatement */
    static boolean containsSynchronizedStatement(Statement s) {
        if (s instanceof SynchronizedStatement) {
            return true;
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream().anyMatch(AstCleanup::containsSynchronizedStatement);
        }
        if (s instanceof TryStatement t) {
            return containsSynchronizedStatement(t.tryBody());
        }
        return false;
    }

/** 从方法体中剥离 synchronized 前导代码(DUP/ASTORE),
     *  生成干净的 {@code synchronized(expr) { body }} 输出.
     *  同时过滤掉从 monitorexit 异常处理器中泄露的处理器伪影
     *  (如 while(true){throw...}). */
    static SynchronizedStatement stripSyncPreamble(SynchronizedStatement syncStmt) {
        Statement body = syncStmt.body();
        String monObj = ((VarExpr) syncStmt.monitorObject()).name();
        if (body instanceof BlockStatement bs) {
            List<Statement> filtered = new ArrayList<>();
            for (Statement s : bs.statements()) {
                // 剥离 "Type varN = this" 前导代码(DUP+ASTRORE 模式)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                        && (vd.name().equals(monObj) || isTypicalSyncTemp(vd))) {
                    continue;
                }
                // 剥离仅为 varN 的 ExpressionStatement(未消费的加载)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.ExpressionStatement es
                        && es.expression() instanceof VarExpr v
                        && v.name().equals(monObj)) {
                    continue;
                }
                // 剥离处理器伪影:while(true){Throwable varN; throw varN;}
                // 这些是 monitorexit 异常处理器泄露到方法体中的
                if (s instanceof LoopStatement loop
                        && loop.loopKind() == LoopStatement.LoopKind.WHILE
                        && loop.condition() instanceof VarExpr vc
                        && "true".equals(vc.name())) {
                    Statement loopBody = loop.body();
                    if (loopBody instanceof BlockStatement lbs) {
                        boolean isHandlerArtifact = false;
                        for (Statement lb : lbs.statements()) {
                            if (lb instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                                    && vd.type().descriptor() != null
                                    && vd.type().descriptor().contains("Throwable")) {
                                isHandlerArtifact = true;
                            }
                        }
                        if (isHandlerArtifact) {
                            continue; // 跳过此处理器伪影
                        }
                    }
                }
                // 同时剥离 synchronized 内部的裸 ThrowStatement(处理器泄露)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.ThrowStatement) {
                    continue;
                }
                // 剥离 Throwable 类型变量声明(monitorexit 处理器伪影)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                        && vd.type() != null && vd.type().descriptor() != null
                        && vd.type().descriptor().contains("Throwable")) {
                    continue;
                }
                // 剥离嵌套的处理器伪影块(仅含 Throwable var + throw 的块)
                if (s instanceof BlockStatement nestedBs) {
                    List<Statement> inner = nestedBs.statements();
                    if (!inner.isEmpty() && inner.stream().allMatch(st ->
                            (st instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                                    && vd.type() != null && vd.type().descriptor() != null
                                    && vd.type().descriptor().contains("Throwable"))
                            || st instanceof com.bingbaihanji.bdec.ast.stmt.ThrowStatement
                            || st == null)) {
                        continue;
                    }
                    if (inner.isEmpty()) {
                        continue; // 空块也跳过
                    }
                }
                // 剥离不可达代码之后的 "return;"(synchronized 体清理)
                if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement r
                        && r.value() == null) {
                    continue;
                }
                filtered.add(s);
            }
            if (filtered.isEmpty()) {
                // 所有语句均被剥离——返回空体而非原始体(原始体含伪影)
                return new SynchronizedStatement(syncStmt.monitorObject(),
                        new BlockStatement(List.of()));
            }
            if (filtered.size() == 1) {
                return new SynchronizedStatement(syncStmt.monitorObject(), filtered.getFirst());
            }
            return new SynchronizedStatement(syncStmt.monitorObject(), new BlockStatement(filtered));
        }
        return syncStmt;
    }

/** 检查变量声明是否类似于 synchronized 临时副本 */
    static boolean isTypicalSyncTemp(com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd) {
        String name = vd.name();
        return (name.startsWith("var") && name.length() <= 5)
                || (vd.initializer() instanceof VarExpr v && "this".equals(v.name()));
    }

/** Recursively remove catch-duplicate statements from a block. */
    static boolean stripFromBlock(java.util.List<Statement> stmts, int idx,
                                   java.util.Set<String> catchTexts) {
        if (idx >= stmts.size()) return false;
        Statement s = stmts.get(idx);
        if (s == null) return false;
        String text = ComparisonUtils.statementText(s);
        if (catchTexts.contains(text)) {
            stmts.set(idx, null);
            return true;
        }
        // Recurse into nested blocks: check their first statement
        if (s instanceof BlockStatement bs) {
            java.util.List<Statement> inner = new java.util.ArrayList<>(bs.statements());
            boolean changed = false;
            for (int k = 0; k < inner.size(); k++) {
                changed |= stripFromBlock(inner, k, catchTexts);
            }
            if (changed) {
                inner.removeIf(java.util.Objects::isNull);
                stmts.set(idx, new BlockStatement(inner));
            }
        }
        return false;
    }

/**
     * Remove statements that accidentally leaked from catch bodies into
     * the parent method body, recursively checking nested blocks.
     */
    static BlockStatement stripLeakedCatchStmts(BlockStatement root) {
        java.util.List<Statement> stmts = new java.util.ArrayList<>(root.statements());
        boolean changed = false;
        // First, recursively process nested blocks
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof BlockStatement bs) {
                stmts.set(i, stripLeakedCatchStmts(bs));
            }
        }
        // Then strip duplicates at this level
        for (int i = 0; i < stmts.size(); i++) {
            if (stmts.get(i) instanceof TryStatement ts && ts.catchClauses() != null) {
                java.util.Set<String> catchTexts = new java.util.HashSet<>();
                for (var cc : ts.catchClauses()) {
                    if (cc.body() instanceof BlockStatement cb) {
                        for (Statement cs : cb.statements()) {
                            if (cs != null) catchTexts.add(ComparisonUtils.statementText(cs));
                        }
                    }
                }
                // Check immediate successors for duplicates
                for (int j = i + 1; j < stmts.size(); j++) {
                    changed |= stripFromBlock(stmts, j, catchTexts);
                }
            }
        }
        if (!changed) return root;
        stmts.removeIf(java.util.Objects::isNull);
        return new BlockStatement(stmts);
    }

static BlockStatement stripSyncPreambles(BlockStatement root) {
        List<Statement> stmts = new ArrayList<>(root.statements());
        for (int i = 0; i < stmts.size() - 1; i++) {
            if (stmts.get(i + 1) instanceof SynchronizedStatement syncStmt) {
                // 剥离作为 synchronized 临时副本的 VariableDeclaration
                if (stmts.get(i) instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                        && isTypicalSyncTemp(vd)) {
                    stmts.remove(i);
                    i--; // 重新检查此索引处的新元素
                }
                // 剥离 ExpressionStatement "varN"(未消费的加载)
                if (i >= 0 && stmts.get(i) instanceof com.bingbaihanji.bdec.ast.stmt.ExpressionStatement es
                        && es.expression() instanceof VarExpr v
                        && v.name().startsWith("var")) {
                    stmts.remove(i);
                    i--;
                }
            }
        }
        if (stmts.size() == 1) {
            return stmts.getFirst() instanceof BlockStatement bs ? bs
                    : new BlockStatement(stmts);
        }
        return new BlockStatement(stmts);
    }

/**
     * 从 try 体中剥离同样出现在 finally 体中的语句.
     * 基于 Expression 对象的结构化比较而非 toString().
     * 递归处理嵌套的复合语句(IfStatement,LoopStatement 等),
     * 使 if-else 分支内的重复 finally 代码也被剥离.
     */
    static Statement stripDuplicatedFinally(Statement tryBody, Statement finallyBody) {
        List<Statement> finallyStmts = StatementUtils.collectStatements(finallyBody);
        if (finallyStmts.isEmpty()) {
            return tryBody;
        }
        return stripMatchingFinally(tryBody, finallyStmts);
    }

/** 递归地从复合语句中剥离与模式(finally 体语句)匹配的语句,
     *  而非仅处理顶层. */
    static Statement stripMatchingFinally(Statement s, List<Statement> patterns) {
        if (s == null) {
            return null;
        }
        // 如果此语句自身与某模式匹配,则完全移除
        if (ComparisonUtils.matchesAny(s, patterns)) {
            return null;
        }
        // 递归处理 BlockStatement
        switch (s) {
            case BlockStatement bs -> {
                List<Statement> filtered = new ArrayList<>();
                for (Statement child : bs.statements()) {
                    Statement stripped = stripMatchingFinally(child, patterns);
                    if (stripped != null) {
                        filtered.add(stripped);
                    }
                }
                if (filtered.isEmpty()) {
                    return new BlockStatement(List.of());
                }
                if (filtered.size() == 1) {
                    return filtered.getFirst();
                }
                return new BlockStatement(filtered);
            }

            // 递归处理 IfStatement 的分支
            case IfStatement i -> {
                Statement thenStripped = stripMatchingFinally(i.thenBranch(), patterns);
                Statement elseStripped = i.elseBranch() != null
                        ? stripMatchingFinally(i.elseBranch(), patterns) : null;
                return new IfStatement(i.condition(),
                        thenStripped != null ? thenStripped : new BlockStatement(List.of()),
                        elseStripped);
            }

            // 递归处理 LoopStatement 的方法体
            case LoopStatement l -> {
                Statement bodyStripped = stripMatchingFinally(l.body(), patterns);
                return new LoopStatement(
                        l.loopKind(), l.condition(),
                        bodyStripped != null ? bodyStripped : new BlockStatement(List.of()));
            }

            // 递归处理 TryStatement 的 try 体和 catch 体.
            // finally 体本身不剥离——patterns 就是 finally 体的语句,
            // 剥离会导致 finallyBody 整体被匹配为 null,输出空 try.
            case TryStatement t -> {
                Statement tryStripped = stripMatchingFinally(t.tryBody(), patterns);
                List<TryStatement.CatchClause> cc = new ArrayList<>();
                for (var c : t.catchClauses()) {
                    Statement bodyStripped = stripMatchingFinally(c.body(), patterns);
                    cc.add(new TryStatement.CatchClause(
                            c.exceptionType(), c.varName(),
                            bodyStripped != null ? bodyStripped : new BlockStatement(List.of())));
                }
                return new TryStatement(
                        tryStripped != null ? tryStripped : new BlockStatement(List.of()),
                        cc, t.finallyBody());
            }
            default -> {
            }
        }
        return s;
    }

/** 简化常见的布尔冗余模式:
     *  {@code x == true} → {@code x}, {@code x != false} → {@code x},
     *  {@code x == false} → {@code !x}, {@code x != true} → {@code !x},
     *  若 x 为布尔值: {@code x == 0} → {@code !x}, {@code x != 0} → {@code x} */
    static Expression simplifyCondition(Expression cond) {
        if (cond == null) {
            return null;
        }
        if (cond instanceof BinExpr bin) {
            // 简化左侧:x == true → x, x != false → x
            Expression left = simplifyCondition(bin.left());
            Expression right = simplifyCondition(bin.right());
            BinaryOperator op = bin.operator();

            // 检查布尔字面量比较
            boolean rightIsTrue = StatementUtils.isBooleanLit(right, true);
            boolean rightIsFalse = StatementUtils.isBooleanLit(right, false);
            boolean leftIsTrue = StatementUtils.isBooleanLit(left, true);
            boolean leftIsFalse = StatementUtils.isBooleanLit(left, false);

            if ((op == BinaryOperator.EQ && rightIsTrue)
                    || (op == BinaryOperator.NE && rightIsFalse)) {
                return left;
            }
            if ((op == BinaryOperator.EQ && rightIsFalse)
                    || (op == BinaryOperator.NE && rightIsTrue)) {
                return new UnExpr(UnaryOperator.NOT, left);
            }
            if ((op == BinaryOperator.EQ && leftIsTrue)
                    || (op == BinaryOperator.NE && leftIsFalse)) {
                return right;
            }
            if ((op == BinaryOperator.EQ && leftIsFalse)
                    || (op == BinaryOperator.NE && leftIsTrue)) {
                return new UnExpr(UnaryOperator.NOT, right);
            }

            // 用简化后的子表达式重建
            if (left != bin.left() || right != bin.right()) {
                return new BinExpr(op, left, right);
            }
        }
        // !!x → x (双重否定消除)
        if (cond instanceof UnExpr un && un.operator() == UnaryOperator.NOT
                && un.operand() instanceof UnExpr inner
                && inner.operator() == UnaryOperator.NOT) {
            return simplifyCondition(inner.operand());
        }
        // 比较取反简化:!(a OP b) → a OP' b
        // 例:!(i >= 10) → i < 10, !(j > 0) → j <= 0
        if (cond instanceof UnExpr un && un.operator() == UnaryOperator.NOT
                && un.operand() instanceof BinExpr inner) {
            BinaryOperator inverted = switch (inner.operator()) {
                case EQ -> BinaryOperator.NE;
                case NE -> BinaryOperator.EQ;
                case LT -> BinaryOperator.GE;
                case GT -> BinaryOperator.LE;
                case LE -> BinaryOperator.GT;
                case GE -> BinaryOperator.LT;
                default -> null;
            };
            if (inverted != null) {
                return new BinExpr(inverted,
                        simplifyCondition(inner.left()), simplifyCondition(inner.right()));
            }
        }
        return cond;
    }
}
