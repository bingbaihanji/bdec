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
 * Post-processing pass that merges adjacent try-catch-finally blocks
 * sharing the same exception handler into a single unified TryStatement.
 *
 * <p>Inspired by Vineflower's {@code FinallyProcessor} which iterates
 * the structured statement graph to detect and merge duplicate code
 * across try-exit and catch-all handler paths.
 *
 * <p>Pattern detected (from RingBuffer.offer):
 * <pre>
 *   try { lock.unlock(); return false; } finally { lock.unlock(); throw e; }
 *   try { enqueue(e); return true; }    finally { lock.unlock(); throw e; }
 *   → merged into one try-finally with combined try body
 * </pre>
 */
public final class FinallyRecognizer {

    /**
     * Merge adjacent try-catch-finally blocks that share the same handler.
     *
     * @param root       the top-level BlockStatement from BlockReducer
     * @param tryCatchAnns map from try-entry-block → TryCatchInfo
     * @return the merged statement tree (may be the same object if no changes)
     */
    public BlockStatement merge(BlockStatement root,
                                 Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        List<Statement> merged = mergeList(root.statements(), tryCatchAnns);
        return new BlockStatement(merged);
    }

    private List<Statement> mergeList(List<Statement> stmts,
                                       Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        // Build index: for each TryStatement, find its handler block
        // We use TryCatchInfo.handlerBlock() as the merge key
        Map<Statement, BasicBlock> tryToHandler = new HashMap<>();
        for (Statement s : stmts) {
            findTryStatements(s, tryCatchAnns, tryToHandler, new HashSet<>());
        }

        // Merge: walk the flat list, combine adjacent TryStatements
        // with the same handler into one
        List<Statement> result = new ArrayList<>();
        List<Statement> pendingTryBodies = null;
        Statement pendingFinally = null;
        BasicBlock pendingHandler = null;

        for (Statement s : stmts) {
            BasicBlock handler = tryToHandler.get(s);

            if (handler != null && s instanceof TryStatement ts && ts.finallyBody() != null) {
                // This is a try-finally. Check if it should be merged with the pending group.
                if (pendingHandler != null && pendingHandler.equals(handler)) {
                    // Merge: add this try body to the pending group
                    pendingTryBodies.add(ts.tryBody());
                    // Keep the first finally body (they're the same handler)
                    continue;
                }

                // Flush any pending group before starting a new one
                if (pendingTryBodies != null && !pendingTryBodies.isEmpty()) {
                    result.add(buildMergedTry(pendingTryBodies, pendingFinally));
                }

                // Start new pending group
                pendingTryBodies = new ArrayList<>();
                pendingTryBodies.add(ts.tryBody());
                pendingFinally = ts.finallyBody();
                pendingHandler = handler;
            } else {
                // Non-try statement: flush pending group first
                if (pendingTryBodies != null && !pendingTryBodies.isEmpty()) {
                    result.add(buildMergedTry(pendingTryBodies, pendingFinally));
                    pendingTryBodies = null;
                    pendingFinally = null;
                    pendingHandler = null;
                }
                result.add(s);
            }
        }

        // Flush final pending group
        if (pendingTryBodies != null && !pendingTryBodies.isEmpty()) {
            result.add(buildMergedTry(pendingTryBodies, pendingFinally));
        }

        // Recursively process nested statements
        List<Statement> processed = new ArrayList<>();
        for (Statement s : result) {
            processed.add(processNested(s, tryCatchAnns));
        }
        return processed;
    }

    /** Build a merged TryStatement from multiple try bodies and one shared finally. */
    private TryStatement buildMergedTry(List<Statement> tryBodies, Statement finallyBody) {
        Statement mergedBody;
        if (tryBodies.size() == 1) {
            mergedBody = tryBodies.get(0);
        } else {
            mergedBody = new BlockStatement(new ArrayList<>(tryBodies));
        }
        return new TryStatement(mergedBody, List.of(), finallyBody);
    }

    /** Recursively process nested BlockStatements. */
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
     * Map TryStatements to their handler blocks using tryCatchAnns.
     * We detect which TryStatement was created from which TryCatchInfo
     * by looking at the try body's structure (the try entry block ID
     * is stored during buildTryCatch).
     */
    private void findTryStatements(Statement s,
                                    Map<BasicBlock, TryCatchInfo> tryCatchAnns,
                                    Map<Statement, BasicBlock> result,
                                    Set<Statement> visited) {
        if (!visited.add(s)) {
            return;
        }
        if (s instanceof TryStatement ts && ts.finallyBody() != null) {
            // Find the matching TryCatchInfo by checking the try body
            for (var entry : tryCatchAnns.entrySet()) {
                BasicBlock tryEntry = entry.getKey();
                // We match heuristically: if this is a try-finally (no catch clauses),
                // it likely came from a catch-all handler.
                // Map it to the handler block for merging.
                if (ts.catchClauses().isEmpty()) {
                    result.put(s, entry.getValue().handlerBlock());
                    break;
                }
            }
        }
        // Recurse into children
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
