package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * finally 块识别与合并器.
 *
 * <p>后处理遍历,将共享同一异常处理器的相邻 try-catch-finally 块
 * 合并为单个统一的 TryStatement.
 *
 * <p>设计参考了 Vineflower 的 {@code FinallyProcessor},
 * 该处理器遍历结构化语句图以检测并合并 try-exit 路径和 catch-all 处理器
 * 路径上的重复代码.
 *
 * <p>检测的模式(源自 RingBuffer.offer 示例):
 * <pre>
 *   try { lock.unlock(); return false; } finally { lock.unlock(); throw e; }
 *   try { enqueue(e); return true; }    finally { lock.unlock(); throw e; }
 *   → 合并为单个 try-finally,try 体为二者组合
 * </pre>
 */
public final class FinallyRecognizer {

    /**
     * 合并共享同一处理器的相邻 try-catch-finally 块.
     *
     * @param root         BlockReducer 生成的顶层 BlockStatement
     * @param tryCatchAnns try 入口块 → TryCatchInfo 的映射
     * @return 合并后的语句树(若无变更则返回同一对象)
     */
    public BlockStatement merge(BlockStatement root,
                                Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        List<Statement> merged = mergeList(root.statements(), tryCatchAnns);
        return new BlockStatement(merged);
    }

    /**
     * 在语句列表中合并共享同一处理器的相邻 try-finally 块.
     *
     * @param stmts        语句列表
     * @param tryCatchAnns try 入口块 → TryCatchInfo 映射
     * @return 合并后的语句列表
     */
    private List<Statement> mergeList(List<Statement> stmts,
                                      Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        // 构建索引:对每个 TryStatement 找到其处理器块,
        // 使用 TryCatchInfo.handlerBlock() 作为合并键
        Map<Statement, BasicBlock> tryToHandler = new HashMap<>();
        for (Statement s : stmts) {
            findTryStatements(s, tryCatchAnns, tryToHandler, new HashSet<>());
        }

        // 合并:遍历扁平的语句列表,将具有相同处理器的相邻 TryStatement 合并为一个
        List<Statement> result = new ArrayList<>();
        List<Statement> pendingTryBodies = null;
        Statement pendingFinally = null;
        BasicBlock pendingHandler = null;

        for (Statement s : stmts) {
            BasicBlock handler = tryToHandler.get(s);

            if (handler != null && s instanceof TryStatement ts && ts.finallyBody() != null) {
                // 这是一个 try-finally.检查是否应与待处理组合并.
                if (pendingHandler != null && pendingHandler.equals(handler)) {
                    // 合并:将此 try 体添加到待处理组
                    pendingTryBodies.add(ts.tryBody());
                    // 保留第一个 finally 体(它们共享同一个处理器)
                    continue;
                }

                // 在开始新组之前刷新待处理组
                if (pendingTryBodies != null && !pendingTryBodies.isEmpty()) {
                    result.add(buildMergedTry(pendingTryBodies, pendingFinally));
                }

                // 开始新的待处理组
                pendingTryBodies = new ArrayList<>();
                pendingTryBodies.add(ts.tryBody());
                pendingFinally = ts.finallyBody();
                pendingHandler = handler;
            } else {
                // 非 try 语句:先刷新待处理组
                if (pendingTryBodies != null && !pendingTryBodies.isEmpty()) {
                    result.add(buildMergedTry(pendingTryBodies, pendingFinally));
                    pendingTryBodies = null;
                    pendingFinally = null;
                    pendingHandler = null;
                }
                result.add(s);
            }
        }

        // 刷新最后的待处理组
        if (pendingTryBodies != null && !pendingTryBodies.isEmpty()) {
            result.add(buildMergedTry(pendingTryBodies, pendingFinally));
        }

        // 递归处理嵌套语句
        List<Statement> processed = new ArrayList<>();
        for (Statement s : result) {
            processed.add(processNested(s, tryCatchAnns));
        }
        return processed;
    }

    /** 从多个 try 体和单个共享 finally 构建合并后的 TryStatement */
    private TryStatement buildMergedTry(List<Statement> tryBodies, Statement finallyBody) {
        Statement mergedBody;
        if (tryBodies.size() == 1) {
            mergedBody = tryBodies.get(0);
        } else {
            mergedBody = new BlockStatement(new ArrayList<>(tryBodies));
        }
        return new TryStatement(mergedBody, List.of(), finallyBody);
    }

    /** 递归处理嵌套的 BlockStatement */
    private Statement processNested(Statement s, Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        if (s instanceof BlockStatement bs) {
            List<Statement> merged = mergeList(bs.statements(), tryCatchAnns);
            return new BlockStatement(merged);
        }
        if (s instanceof IfStatement ifStmt) {
            Statement thenBody = processNested(ifStmt.thenBranch(), tryCatchAnns);
            Statement elseBody = ifStmt.elseBranch() != null
                    ? processNested(ifStmt.elseBranch(), tryCatchAnns) : null;
            return new IfStatement(ifStmt.condition(), thenBody, elseBody);
        }
        if (s instanceof LoopStatement loop) {
            Statement body = processNested(loop.body(), tryCatchAnns);
            return new LoopStatement(loop.loopKind(), loop.condition(), body);
        }
        if (s instanceof TryStatement ts) {
            Statement tryBody = processNested(ts.tryBody(), tryCatchAnns);
            List<TryStatement.CatchClause> catchClauses = ts.catchClauses();
            Statement finallyBody = ts.finallyBody() != null
                    ? processNested(ts.finallyBody(), tryCatchAnns) : null;
            return new TryStatement(tryBody, catchClauses, finallyBody);
        }
        return s;
    }

    /**
     * 使用 tryCatchAnns 将 TryStatement 映射到其处理器块.
     * 通过检查 try 体的结构(try 入口块 ID 在 buildTryCatch 期间存储)
     * 来推断哪个 TryStatement 源自哪个 TryCatchInfo.
     */
    private void findTryStatements(Statement s,
                                   Map<BasicBlock, TryCatchInfo> tryCatchAnns,
                                   Map<Statement, BasicBlock> result,
                                   Set<Statement> visited) {
        if (!visited.add(s)) {
            return;
        }
        if (s instanceof TryStatement ts && ts.finallyBody() != null) {
            // 通过检查 try 体找到匹配的 TryCatchInfo
            for (var entry : tryCatchAnns.entrySet()) {
                // 启发式匹配:如果是 try-finally(无 catch 子句),
                // 大概率来自 catch-all 处理器.将其映射到处理器块用于合并.
                if (ts.catchClauses().isEmpty()) {
                    result.put(s, entry.getValue().handlerBlock());
                    break;
                }
            }
        }
        // 递归处理子语句
        if (s instanceof BlockStatement bs) {
            for (Statement child : bs.statements()) {
                findTryStatements(child, tryCatchAnns, result, visited);
            }
        }
        if (s instanceof IfStatement ifStmt) {
            findTryStatements(ifStmt.thenBranch(), tryCatchAnns, result, visited);
            if (ifStmt.elseBranch() != null) {
                findTryStatements(ifStmt.elseBranch(), tryCatchAnns, result, visited);
            }
        }
        if (s instanceof LoopStatement loop) {
            findTryStatements(loop.body(), tryCatchAnns, result, visited);
        }
        if (s instanceof TryStatement ts) {
            findTryStatements(ts.tryBody(), tryCatchAnns, result, visited);
            if (ts.finallyBody() != null) {
                findTryStatements(ts.finallyBody(), tryCatchAnns, result, visited);
            }
        }
    }
}
