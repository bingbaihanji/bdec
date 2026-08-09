package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InstanceOfExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
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
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a structured CFG into AST statements by translating
 * {@link IrInstruction} objects into proper AST expression/statement nodes.
 *
 * Key design: Only side-effecting instructions (STORE/RETURN/THROW/INVOKE/etc.)
 * become statements. Intermediate instructions (LOAD/CONST/BINARY/CAST/etc.)
 * are resolved into expression trees by following {@link InstructionRef} chains.
 */
public final class BlockReducer {

    private final boolean isInstanceMethod;

    // Transient state for NEW+INIT merging (CondenseConstruction pattern)
    private Map<Integer, List<IrInstruction>> currentNewToInit = Map.of();

    private Set<Integer> currentInitToSkip = Set.of();

    // Transient state for constant/value inlining (STORE→Variable→LOAD chains)
    private Map<Variable, Value> currentVarStoreSource = Map.of();

    private Set<Integer> currentStoresToSkip = Set.of();

    // Branch context for PHI resolution: when translating a branch body,
    // records which block IDs belong to the branch so PHI nodes can pick
    // the correct operand.
    private Set<Integer> currentBranchBlocks = null;

    // Cached from the method being decompiled
    private boolean currentMethodReturnsBoolean = false;

    public BlockReducer() {this(true);}

    public BlockReducer(boolean isInstanceMethod) {
        this.isInstanceMethod = isInstanceMethod;
    }

    /** Check if an instruction produces a side effect that should become a statement. */
    private static boolean isStatementRoot(IrInstruction insn) {
        return switch (insn.opcode()) {
            case STORE, RETURN, THROW, FIELD_STORE, ARRAY_STORE -> true;
            case INVOKE -> insn.resultType().kind() == TypeKind.VOID
                    || insn.resultType().kind() == null; // void invocations
            case INC -> true; // IINC is always a statement
            case MONITOR_ENTER, MONITOR_EXIT -> true;
            default -> false;
        };
    }

    /** Check if an expression is "ignorable" — just a naked variable or temp ref. */
    private static boolean isIgnorableExpr(Expression e) {
        if (e instanceof VarExpr v) {
            String name = v.name();
            return name.startsWith("var") || name.startsWith("tmp") || name.startsWith("?")
                    || "this".equals(name);
        }
        return false;
    }

    /** Check if a statement block is empty or contains only empty blocks. */
    private static boolean isEmptyBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            return bs.statements().isEmpty()
                    || bs.statements().stream().allMatch(BlockReducer::isEmptyBlock);
        }
        return false;
    }

    private static boolean isBooleanLit(Expression e, boolean expected) {
        if (e instanceof LitExpr lit) {
            Object v = lit.value();
            return v instanceof Boolean b && b == expected;
        }
        return false;
    }

    // ── Block grouping ────────────────────────────────────────────────

    /** Detect increment/decrement: x = x + 1 → x++, x = x - 1 → x--. */
    private static UnaryOperator detectIncrement(BinExpr bin) {
        boolean isOne = bin.right() instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                && lr.value() instanceof Integer i && i == 1;
        if (!isOne) {
            return null;
        }
        if (bin.operator() == BinaryOperator.ADD) {
            return com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC;
        }
        if (bin.operator() == BinaryOperator.SUB) {
            return com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC;
        }
        return null;
    }

    /** Check if a statement matches any in a list by comparing expression structure. */
    private static boolean matchesAny(Statement s, List<Statement> candidates) {
        if (s instanceof ExpressionStatement es) {
            for (Statement c : candidates) {
                if (c instanceof ExpressionStatement ce
                        && expressionsEquivalent(es.expression(), ce.expression())) {
                    return true;
                }
            }
        }
        if (s instanceof ReturnStatement rs && rs.value() != null) {
            for (Statement c : candidates) {
                if (c instanceof ReturnStatement rc && rc.value() != null
                        && expressionsEquivalent(rs.value(), rc.value())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Structural comparison of two Expression trees. */
    private static boolean expressionsEquivalent(Expression a, Expression b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }

        if (a instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr ia
                && b instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr ib) {
            if (!ia.methodName().equals(ib.methodName())) {
                return false;
            }
            if (ia.arguments().size() != ib.arguments().size()) {
                return false;
            }
            for (int i = 0; i < ia.arguments().size(); i++) {
                if (!expressionsEquivalent(ia.arguments().get(i), ib.arguments().get(i))) {
                    return false;
                }
            }
            return expressionsEquivalent(ia.target(), ib.target());
        }
        if (a instanceof com.bingbaihanji.bdec.ast.expr.LitExpr la
                && b instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lb) {
            Object va = la.value(), vb = lb.value();
            return va == null ? vb == null : va.equals(vb);
        }
        if (a instanceof com.bingbaihanji.bdec.ast.expr.VarExpr va
                && b instanceof com.bingbaihanji.bdec.ast.expr.VarExpr vb) {
            return va.name().equals(vb.name());
        }
        if (a instanceof com.bingbaihanji.bdec.ast.expr.FieldAccessExpr fa
                && b instanceof com.bingbaihanji.bdec.ast.expr.FieldAccessExpr fb) {
            return fa.fieldName().equals(fb.fieldName())
                    && expressionsEquivalent(fa.target(), fb.target());
        }
        return false;
    }

    /** Recursively collect all statements, flattening nested BlockStatements. */
    private static List<Statement> collectStatements(Statement s) {
        List<Statement> result = new ArrayList<>();
        if (s instanceof BlockStatement bs) {
            for (Statement child : bs.statements()) {
                result.addAll(collectStatements(child));
            }
        } else {
            result.add(s);
        }
        return result;
    }

    /** Check if a value is simple enough to inline (constant or basic expression). */
    private static boolean isSimpleValue(Value v) {
        if (v instanceof ConstantValue) {
            return true;
        }
        if (v instanceof InstructionRef ref) {
            IrOpcode op = ref.instruction().opcode();
            return op == IrOpcode.CONST || op == IrOpcode.LOAD || op == IrOpcode.CAST
                    || op == IrOpcode.FIELD_LOAD || op == IrOpcode.ARRAY_LENGTH
                    || op == IrOpcode.INSTANCE_OF;
        }
        return false;
    }

    /** Check if a statement tree contains a ReturnStatement. */
    private static boolean hasReturnStmt(Statement s) {
        if (s instanceof ReturnStatement) {
            return true;
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream().anyMatch(BlockReducer::hasReturnStmt);
        }
        return false;
    }

    /** Strip orphan ExpressionStatements from a branch body that already
     *  has a ReturnStatement (they're noise from block ordering at merge points). */
    private static Statement stripOrphanExprs(Statement s) {
        if (s instanceof BlockStatement bs) {
            boolean hasAnyReturn = bs.statements().stream().anyMatch(BlockReducer::hasReturnStmt);
            if (!hasAnyReturn) {
                return s;
            }
            List<Statement> filtered = new ArrayList<>();
            for (Statement child : bs.statements()) {
                if (child instanceof ExpressionStatement) {
                    continue; // strip orphan CONST
                }
                if (child instanceof BlockStatement) {
                    Statement stripped = stripOrphanExprs(child);
                    if (!isEmptyBlock(stripped)) {
                        filtered.add(stripped);
                    }
                } else {
                    filtered.add(child);
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
        return s;
    }

    /** Wrap a branch body's ExpressionStatements as ReturnStatements
     *  (handles orphan CONSTs in branches without their own RETURN). */
    private static Statement wrapAsReturn(Statement s, boolean isBoolRet) {
        if (s instanceof BlockStatement bs) {
            if (hasReturnStmt(s)) {
                return s; // already has RETURN
            }
            List<Statement> result = new ArrayList<>();
            for (Statement child : bs.statements()) {
                if (child instanceof ExpressionStatement es) {
                    result.add(new ReturnStatement(boolLiteral(es.expression(), isBoolRet)));
                } else if (child instanceof BlockStatement inner) {
                    result.add(wrapAsReturn(inner, isBoolRet));
                } else {
                    result.add(child);
                }
            }
            if (result.isEmpty()) {
                return new BlockStatement(List.of());
            }
            if (result.size() == 1) {
                return result.getFirst();
            }
            return new BlockStatement(result);
        }
        if (s instanceof ExpressionStatement es) {
            return new ReturnStatement(boolLiteral(es.expression(), isBoolRet));
        }
        return s;
    }

    /** Convert integer literal to boolean for boolean-return methods. */
    private static Expression boolLiteral(Expression e, boolean isBoolRet) {
        if (isBoolRet && e instanceof LitExpr lit && lit.value() instanceof Integer i) {
            return new LitExpr(i != 0, JavaType.BOOLEAN);
        }
        return e;
    }

    /**
     * Post-processing: wrap statement groups in try-catch based on CFG exception ranges.
     * Runs AFTER if/else/loop structuring so nested control structures are preserved.
     *
     * <p>The key insight: we track which original blocks belong to each try range,
     * reassemble the final statements back into their groups, find which groups
     * have all their blocks inside a try range, and wrap only those.
     */
    private BlockStatement wrapTryCatchBlocks(BlockStatement root,
                                              List<BlockGroup> groups,
                                              Map<BasicBlock, TryCatchInfo> tryCatchAnns,
                                              LinearIr ir) {
        if (tryCatchAnns.isEmpty()) {
            return root;
        }

        List<Statement> stmts = new ArrayList<>(root.statements());

        // For each try-catch annotation, find the contiguous range of groups
        // whose blocks are all within the try range
        for (var entry : tryCatchAnns.entrySet()) {
            TryCatchInfo tci = entry.getValue();

            // Find groups that contain ONLY try-range blocks
            int firstTryGroup = -1;
            int lastTryGroup = -1;
            for (int i = 0; i < groups.size(); i++) {
                boolean allInTry = true;
                boolean anyInTry = false;
                for (BasicBlock b : groups.get(i).blocks()) {
                    if (tci.tryBlocks().contains(b)) {
                        anyInTry = true;
                    } else {
                        allInTry = false;
                    }
                }
                if (anyInTry && allInTry) {
                    if (firstTryGroup < 0) {
                        firstTryGroup = i;
                    }
                    lastTryGroup = i;
                }
            }

            if (firstTryGroup < 0 || firstTryGroup >= stmts.size()) {
                continue;
            }

            // For finally patterns (catch-all handler), extend the try body
            // to include the normal-exit block that follows the try range.
            // The normal-exit block (at endPc) contains:
            //   [finally body copy] [return value]
            // We want: try { return value; } finally { ... }
            // The duplicated finally code in the normal exit is stripped.
            boolean isFinally = tci.catchType() == null
                    || "java/lang/Throwable".equals(tci.catchType());

            // Collect the normal-exit groups (after the try range, before the handler)
            int normalExitEnd = lastTryGroup;
            if (isFinally) {
                // Find groups that contain blocks NOT in tryBlocks and NOT the handler,
                // but that appear between lastTryGroup and the handler group.
                for (int i = lastTryGroup + 1; i < groups.size(); i++) {
                    BlockGroup g = groups.get(i);
                    boolean hasHandler = false;
                    boolean hasTry = false;
                    for (BasicBlock b : g.blocks()) {
                        if (b == tci.handlerBlock()) {
                            hasHandler = true;
                        }
                        if (tci.tryBlocks().contains(b)) {
                            hasTry = true;
                        }
                    }
                    if (hasHandler || hasTry) {
                        break; // stop at handler or next try range
                    }
                    normalExitEnd = i; // include this group
                }
            }

            // Build try body: groups from firstTryGroup to normalExitEnd
            List<Statement> tryBodyStmts = new ArrayList<>();
            for (int i = firstTryGroup; i <= normalExitEnd && i < stmts.size(); i++) {
                tryBodyStmts.add(stmts.get(i));
            }

            if (!tryBodyStmts.isEmpty()) {
                Statement tryBody = tryBodyStmts.size() == 1
                        ? tryBodyStmts.get(0)
                        : new BlockStatement(tryBodyStmts);
                stmts.set(firstTryGroup, buildTryCatch(tci, tryBody, ir));
                // Remove absorbed groups
                for (int i = normalExitEnd; i > firstTryGroup; i--) {
                    if (i < stmts.size()) {
                        stmts.remove(i);
                    }
                }
            }
        }

        return new BlockStatement(stmts);
    }

    public BlockStatement reduce(ControlFlowGraph graph, LinearIr ir,
                                 Map<BasicBlock, LoopInfo> loopAnns,
                                 Map<BasicBlock, IfInfo> ifAnns,
                                 Map<BasicBlock, SwitchInfo> switchAnns,
                                 Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        // Order blocks by dominator-tree preorder (not start offset).
        // This ensures constructor bodies appear in control-flow order.
        List<BasicBlock> sorted = dominatorTreeOrder(graph);
        if (sorted.isEmpty()) {
            return new BlockStatement(List.of());
        }

        // Cache method return type info
        currentMethodReturnsBoolean = ir.method().returnType() != null
                && ir.method().returnType().kind() == TypeKind.BOOLEAN;

        // Compute post-dominator tree once for fallback if-header detection.
        // This gives correct merge points when BranchAnalyzer annotations are missing.
        PostDominatorTree postDom = PostDominatorTree.compute(graph);

        List<BlockGroup> groups = groupAdjacentBlocks(sorted, graph);
        Set<BlockGroup> consumed = new HashSet<>();
        // Collect handler blocks so we can skip their groups (they'll be
        // absorbed into try-finally by wrapTryCatchBlocks).
        Set<BasicBlock> handlerBlocks = new HashSet<>();
        for (TryCatchInfo tci : tryCatchAnns.values()) {
            handlerBlocks.add(tci.handlerBlock());
        }
        List<Statement> statements = new ArrayList<>();

        // ── Global variable inlining pre-pass ─────────────────────────
        // Scan ALL groups to find STORE→Variable→LOAD chains where the
        // variable is used exactly once. This works across group boundaries
        // (critical for try-finally where STORE is in the try body group
        // and LOAD+RETURN is in the normal-exit group).
        buildGlobalVarInlineMap(groups, ir);

        for (int gi = 0; gi < groups.size(); gi++) {
            BlockGroup group = groups.get(gi);
            if (consumed.contains(group)) {
                continue;
            }
            consumed.add(group);

            // Find matching annotation — check all blocks in the group,
            // not just group.first(), since CFG folding may have merged
            // the annotation header with preceding blocks.
            IfInfo ifInfo = findIfAnnotation(group, ifAnns);
            LoopInfo loopInfo = findLoopAnnotation(group, loopAnns);
            TryCatchInfo tryCatchInfo = findTryAnnotation(group, tryCatchAnns);
            SwitchInfo switchInfo = findSwitchAnnotation(group, switchAnns);

            // Fallback: if no IfInfo annotation found, try to detect if-header
            // directly from CFG structure (condition block with 2 successors).
            // Uses post-dominator tree for correct merge point computation.
            if (ifInfo == null) {
                ifInfo = detectIfHeader(group, graph, ir, postDom);
            }

            Statement s;

            // if-else: build proper IfStatement with both then and else bodies
            if (ifInfo != null) {
                Expression cond = simplifyCondition(extractCondition(group, ir));

                // Translate then-body: find the group(s) containing then-blocks
                Statement thenBody = translateBranchBody(ifInfo.thenBlocks(), groups, ir, consumed);

                // Translate else-body: find the group(s) containing else-blocks
                Statement elseBody = null;
                if (!ifInfo.elseBlocks().isEmpty()) {
                    elseBody = translateBranchBody(ifInfo.elseBlocks(), groups, ir, consumed);
                }

                // Eliminate empty else blocks — don't emit "else { }"
                if (elseBody != null && isEmptyBlock(elseBody)) {
                    elseBody = null;
                }

                // Post-process branch bodies for if-else patterns where both
                // branches compute values that merge at a common RETURN block.
                // Due to IrBuilder DFS ordering, one branch's value gets consumed
                // by the RETURN while the other branch's value is an orphan CONST.
                // Clean up: strip orphan CONSTs from branches with RETURN, and
                // wrap orphan CONSTs as RETURN for branches without RETURN.
                boolean thenHasReturn = hasReturnStmt(thenBody);
                boolean elseHasReturn = elseBody != null && hasReturnStmt(elseBody);
                boolean isBoolRet = ir.method().returnType() != null
                        && ir.method().returnType().kind() == TypeKind.BOOLEAN;

                if (thenHasReturn != elseHasReturn) {
                    if (thenHasReturn) {
                        // Strip orphan expressions from then (they're noise)
                        thenBody = stripOrphanExprs(thenBody);
                        // Wrap orphan expressions in else as return
                        if (elseBody != null) {
                            elseBody = wrapAsReturn(elseBody, isBoolRet);
                        }
                    } else {
                        if (thenBody != null) {
                            thenBody = wrapAsReturn(thenBody, isBoolRet);
                        }
                        elseBody = elseBody != null ? stripOrphanExprs(elseBody) : null;
                    }
                }

                s = new IfStatement(cond != null ? cond : new VarExpr("/*condition*/"),
                        thenBody != null ? thenBody : new BlockStatement(List.of()),
                        elseBody);
            }
            // loop: wrap group in LoopStatement (only if we have a valid body)
            else if (loopInfo != null) {
                s = translateGroup(group, ir);
                if (s != null && !isEmptyBlock(s)) {
                    Expression cond = simplifyCondition(extractCondition(group, ir));
                    s = new LoopStatement(LoopStatement.LoopKind.WHILE,
                            cond != null ? cond : new VarExpr("true"), s);
                }
            }
            // switch
            else if (switchInfo != null) {
                s = buildSwitch(switchInfo, group, ir, groups, consumed);
            }
            // try-catch: defer wrapping to post-processing pass (wrapTryCatchBlocks)
            // This ensures try ranges that contain if/else/loop structures
            // are properly wrapped AFTER inner structures are built.
            else if (tryCatchInfo != null) {
                s = translateGroup(group, ir);
            }
            // Handler-only groups (pure exception handlers) are absorbed into
            // try-finally by wrapTryCatchBlocks — skip them to avoid dead code.
            else if (group.blocks().size() == 1 && handlerBlocks.contains(group.first())) {
                continue; // skip handler block — absorbed by try-finally
            }
            // synchronized
            else if (groupHasSynchronizedAnnotation(group, ir)) {
                s = translateGroup(group, ir);
                if (s != null) {
                    s = wrapSynchronized(s, group, ir);
                }
            }
            // plain sequence
            else {
                s = translateGroup(group, ir);
            }

            if (s != null) {
                statements.add(s);
            }
        }
        // Post-process: wrap statement groups in try-catch based on annotations.
        // Avoid double-wrapping: if there's only one statement and it's already
        // a BlockStatement, use it directly as the root instead of nesting.
        BlockStatement root;
        if (statements.size() == 1 && statements.getFirst() instanceof BlockStatement bs) {
            root = bs;
        } else {
            root = new BlockStatement(statements);
        }
        return wrapTryCatchBlocks(root, groups, tryCatchAnns, ir);
    }

    /** Find if any block in the group has an IfInfo annotation. */
    private IfInfo findIfAnnotation(BlockGroup group, Map<BasicBlock, IfInfo> ifAnns) {
        for (BasicBlock b : group.blocks()) {
            if (ifAnns.containsKey(b)) {
                return ifAnns.get(b);
            }
        }
        return null;
    }

    // ── Group → Statement translation ──────────────────────────────

    private LoopInfo findLoopAnnotation(BlockGroup group, Map<BasicBlock, LoopInfo> loopAnns) {
        for (BasicBlock b : group.blocks()) {
            if (loopAnns.containsKey(b)) {
                return loopAnns.get(b);
            }
        }
        return null;
    }

    private TryCatchInfo findTryAnnotation(BlockGroup group, Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        for (BasicBlock b : group.blocks()) {
            if (tryCatchAnns.containsKey(b)) {
                return tryCatchAnns.get(b);
            }
        }
        return null;
    }

    private SwitchInfo findSwitchAnnotation(BlockGroup group, Map<BasicBlock, SwitchInfo> switchAnns) {
        for (BasicBlock b : group.blocks()) {
            if (switchAnns.containsKey(b)) {
                return switchAnns.get(b);
            }
        }
        return null;
    }

    // ── IR → Statement ─────────────────────────────────────────────

    /**
     * Detect if-header directly from CFG structure, bypassing BranchAnalyzer.
     * Checks: the group's last block has a CONDITION instruction and exactly
     * 2 outgoing TRUE_BRANCH/FALSE_BRANCH edges.
     *
     * Uses the post-dominator tree to find the correct merge point (follow)
     * instead of hardcoding Exit. This is critical for correct if-else
     * detection: without the correct follow, both branch block sets would
     * include all code after the if, causing the first branch to consume
     * all groups and leaving nothing for the second branch.
     */
    private IfInfo detectIfHeader(BlockGroup group, ControlFlowGraph graph, LinearIr ir,
                                  PostDominatorTree postDom) {
        for (BasicBlock b : group.blocks()) {
            // Check if any instruction in this block is a CONDITION
            boolean hasCondition = ir.instructionsOf(b).stream()
                    .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
            if (!hasCondition) {
                continue;
            }

            // Find TRUE_BRANCH and FALSE_BRANCH edges
            BasicBlock trueTarget = null, falseTarget = null;
            for (var edge : graph.outgoingOf(b)) {
                if (edge.kind() == EdgeKind.TRUE_BRANCH) {
                    trueTarget = edge.target();
                } else if (edge.kind() == EdgeKind.FALSE_BRANCH) {
                    falseTarget = edge.target();
                }
            }
            if (trueTarget == null && falseTarget == null) {
                continue;
            }
            // Fill missing with remaining successors
            List<BasicBlock> succs = graph.successorsOf(b);
            if (trueTarget == null && succs.size() >= 1) {
                for (BasicBlock s : succs) {
                    if (s != falseTarget) {
                        trueTarget = s;
                        break;
                    }
                }
            }
            if (falseTarget == null && succs.size() >= 1) {
                for (BasicBlock s : succs) {
                    if (s != trueTarget) {
                        falseTarget = s;
                        break;
                    }
                }
            }
            if (trueTarget == null || falseTarget == null) {
                continue;
            }

            // Compute the merge point using post-dominator tree.
            // This is the first block that must be traversed on all paths
            // from the condition block to Exit. For if-return-else-return,
            // this is Exit itself. For if-else with merge, this is the join point.
            BasicBlock follow = postDom.immediatePostDominator(b);
            if (follow == null) {
                follow = graph.exitBlock();
            }

            // If one successor IS the follow, this is if-then (no else).
            // If neither successor is the follow, both branches eventually
            // reach follow → if-else.
            Set<BasicBlock> thenBlocks, elseBlocks;
            if (trueTarget == follow) {
                // true branch goes to follow → false branch is the "then" body
                thenBlocks = collectReachableBlocks(falseTarget, follow, graph);
                elseBlocks = Set.of();
            } else if (falseTarget == follow) {
                // false branch goes to follow → true branch is the "then" body
                thenBlocks = collectReachableBlocks(trueTarget, follow, graph);
                elseBlocks = Set.of();
            } else {
                // Both branches reach follow → if-else
                thenBlocks = collectReachableBlocks(trueTarget, follow, graph);
                elseBlocks = collectReachableBlocks(falseTarget, follow, graph);
            }
            return new IfInfo(b, follow, thenBlocks, elseBlocks);
        }
        return null;
    }

    // ── IR → Expression ────────────────────────────────────────────

    /** Collect all blocks reachable from start up to (but not including) stop. */
    private Set<BasicBlock> collectReachableBlocks(BasicBlock start, BasicBlock stop,
                                                   ControlFlowGraph graph) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock curr = queue.poll();
            if (curr == stop || !result.add(curr)) {
                continue;
            }
            for (BasicBlock succ : graph.successorsOf(curr)) {
                if (succ != stop) {
                    queue.add(succ);
                }
            }
        }
        return result;
    }

    /**
     * Translate the blocks belonging to one branch (then or else) of an if-statement.
     * Consumes the matching groups so they aren't emitted again.
     *
     * Checks if any block in a group belongs to the branch (not just the first),
     * so groups formed after CFG folding that start with non-branch blocks
     * are still correctly matched.
     */
    private Statement translateBranchBody(Set<BasicBlock> branchBlocks,
                                          List<BlockGroup> allGroups,
                                          LinearIr ir,
                                          Set<BlockGroup> consumed) {
        // Set branch context for PHI resolution
        Set<Integer> prevBranchBlocks = currentBranchBlocks;
        Set<Integer> branchBlockIds = new HashSet<>();
        for (BasicBlock b : branchBlocks) {
            branchBlockIds.add(b.id());
        }
        currentBranchBlocks = branchBlockIds;
        try {
            List<Statement> bodyStmts = new ArrayList<>();
            for (BlockGroup g : allGroups) {
                if (consumed.contains(g)) {
                    continue;
                }
                boolean groupInBranch = branchBlocks.contains(g.first());
                if (!groupInBranch) {
                    for (BasicBlock gb : g.blocks()) {
                        if (branchBlocks.contains(gb)) {
                            groupInBranch = true;
                            break;
                        }
                    }
                }
                if (groupInBranch) {
                    consumed.add(g);
                    Statement stmt = translateGroup(g, ir);
                    if (stmt != null) {
                        bodyStmts.add(stmt);
                    }
                }
            }
            if (bodyStmts.isEmpty()) {
                return new BlockStatement(List.of());
            }
            if (bodyStmts.size() == 1 && !(bodyStmts.getFirst() instanceof BlockStatement)) {
                return bodyStmts.getFirst();
            }
            List<Statement> flat = new ArrayList<>();
            for (Statement s : bodyStmts) {
                if (s instanceof BlockStatement bs) {
                    flat.addAll(bs.statements());
                } else {
                    flat.add(s);
                }
            }
            if (flat.size() == 1) {
                return flat.getFirst();
            }
            return new BlockStatement(flat);
        } finally {
            currentBranchBlocks = prevBranchBlocks;
        }
    }

    /** Sort blocks by dominator-tree preorder. */
    private List<BasicBlock> dominatorTreeOrder(ControlFlowGraph graph) {
        DominatorTree dom = graph.dominatorTree();
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        Deque<BasicBlock> stack = new ArrayDeque<>();
        stack.push(graph.entryBlock());
        while (!stack.isEmpty()) {
            BasicBlock b = stack.pop();
            if (!visited.add(b)) {
                continue;
            }
            if (b != graph.entryBlock() && b != graph.exitBlock()
                    && !b.instructions().isEmpty()) {
                result.add(b);
            }
            // Push children in reverse order so first child is processed first
            List<BasicBlock> children = new ArrayList<>(dom.children(b));
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }

    /** Extract a condition expression from CONDITION IR instructions in the group. */
    private Expression extractCondition(BlockGroup group, LinearIr ir) {
        List<IrInstruction> all = group.allIrInstructions(ir);
        for (IrInstruction insn : all) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                return translateExpr(insn);
            }
        }
        return null;
    }

    /** Simplify common boolean redundancy patterns:
     *  {@code x == true} → {@code x}, {@code x != false} → {@code x},
     *  {@code x == false} → {@code !x}, {@code x != true} → {@code !x},
     *  {@code x == 0} where x is boolean → {@code !x},
     *  {@code x != 0} where x is boolean → {@code x}. */
    private Expression simplifyCondition(Expression cond) {
        if (cond == null) {
            return null;
        }
        if (cond instanceof BinExpr bin) {
            // Simplify left side: x == true → x, x != false → x
            Expression left = simplifyCondition(bin.left());
            Expression right = simplifyCondition(bin.right());
            BinaryOperator op = bin.operator();

            // Check for boolean literal comparisons
            boolean rightIsTrue = isBooleanLit(right, true);
            boolean rightIsFalse = isBooleanLit(right, false);
            boolean leftIsTrue = isBooleanLit(left, true);
            boolean leftIsFalse = isBooleanLit(left, false);

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

            // Rebuild with simplified children
            if (left != bin.left() || right != bin.right()) {
                return new BinExpr(op, left, right);
            }
        }
        return cond;
    }

    private List<BlockGroup> groupAdjacentBlocks(List<BasicBlock> blocks, ControlFlowGraph graph) {
        List<BlockGroup> groups = new ArrayList<>();
        BlockGroup current = null;
        for (BasicBlock b : blocks) {
            if (current == null) {
                current = new BlockGroup(b);
            } else if (isAdjacent(current.last(), b, graph)) {
                current.add(b);
            } else {
                groups.add(current);
                current = new BlockGroup(b);
            }
        }
        if (current != null) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * Check if two blocks should be treated as adjacent (same group).
     * Adjacent blocks have a single fallthrough edge from prev to next,
     * AND share the same exception coverage — blocks with different
     * exception handlers should NOT be grouped together, otherwise
     * pre-try code (like lock.lock()) gets merged with the try body
     * and can't be properly wrapped.
     */
    private boolean isAdjacent(BasicBlock prev, BasicBlock next, ControlFlowGraph graph) {
        List<BasicBlock> succs = graph.successorsOf(prev);
        if (succs.size() != 1 || succs.get(0) != next) {
            return false;
        }
        if (!graph.outgoingOf(prev).stream().allMatch(e -> e.kind() == EdgeKind.FALL_THROUGH)) {
            return false;
        }
        // Respect try boundaries: if blocks have different exception coverage
        // (one has exception edges and the other doesn't), don't merge them.
        boolean prevHasException = graph.outgoingOf(prev).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        boolean nextHasException = graph.outgoingOf(next).stream()
                .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
        if (prevHasException != nextHasException) {
            return false;
        }
        return true;
    }

    /**
     * Translate a block group to a statement tree.
     *
     * Only side-effecting instructions (statements) are emitted.
     * Intermediate values (LOAD, BINARY, etc.) are skipped — they contribute
     * to expression trees via recursive {@link #valueToExpr} resolution.
     */
    private Statement translateGroup(BlockGroup group, LinearIr ir) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        if (allInsns.isEmpty()) {
            return null;
        }

        // Build index: which instruction IDs have their results consumed.
        // Track via InstructionRef (standard chain) AND via Variable (for LOAD
        // instructions whose result Variable flows through the stack directly).
        Set<Integer> consumed = new HashSet<>();
        // Map each LOAD instruction's loaded Variable → LOAD id for back-tracking
        Map<Variable, Integer> loadVarToId = new HashMap<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.LOAD && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                loadVarToId.put(v, insn.id());
            }
        }
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                } else if (op instanceof Variable v && loadVarToId.containsKey(v)) {
                    // The Variable from a LOAD is used directly → mark LOAD consumed
                    consumed.add(loadVarToId.get(v));
                }
            }
        }

        // Augment the global var→value inline map with per-group discoveries.
        // Start from the global map (built in reduce()) and add per-group entries.
        Map<Variable, Value> varStoreSource = new HashMap<>(currentVarStoreSource);
        Set<Integer> storableToSkip = new HashSet<>(currentStoresToSkip);
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                    && insn.operands().get(0) instanceof Variable v
                    && !varStoreSource.containsKey(v)) {
                Value source = insn.operands().get(1);
                // Count how many times this STORE's result variable is loaded in this group
                int loadCount = 0;
                for (IrInstruction other : allInsns) {
                    if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                            && other.operands().getFirst() instanceof Variable lv
                            && lv.slot() == v.slot() && lv.version() == v.version()) {
                        loadCount++;
                    }
                }
                if (loadCount == 1 && isSimpleValue(source)) {
                    // Check if the single LOAD is consumed
                    for (IrInstruction other : allInsns) {
                        if (other.opcode() == IrOpcode.LOAD && !other.operands().isEmpty()
                                && other.operands().getFirst() instanceof Variable lv
                                && lv.slot() == v.slot() && lv.version() == v.version()) {
                            if (consumed.contains(other.id())) {
                                varStoreSource.put(v, source);
                                storableToSkip.add(insn.id());
                            }
                            break;
                        }
                    }
                }
            }
        }
        currentVarStoreSource = Collections.unmodifiableMap(varStoreSource);
        currentStoresToSkip = Set.copyOf(storableToSkip);

        // Pre-pass: merge NEW + INVOKE <init> pairs (CondenseConstruction pattern)
        currentNewToInit = new java.util.HashMap<>();
        currentInitToSkip = new java.util.HashSet<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.INVOKE && insn.hasTag(
                    com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION)
                    && !insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                    && !insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)) {
                for (Value op : insn.operands()) {
                    if (op instanceof InstructionRef ref) {
                        IrInstruction def = ref.instruction();
                        if (def.opcode() == IrOpcode.NEW && consumed.contains(def.id())) {
                            currentNewToInit.put(def.id(), List.of(insn));
                            currentInitToSkip.add(insn.id());
                            break;
                        }
                    }
                }
            }
        }

        // Check for synchronized block annotation on any instruction
        boolean isSynchronized = allInsns.stream().anyMatch(
                i -> i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK)
                        && i.opcode() == IrOpcode.MONITOR_ENTER);

        // Only emit root instructions as statements
        List<Statement> stmts = new ArrayList<>();
        for (IrInstruction insn : allInsns) {
            // Skip conditions — they are extracted by IfStatement/LoopStatement wrappers
            // via extractCondition() in reduce(). If the condition block has no matching
            // annotation, emit a comment placeholder so control flow isn't silently lost.
            if (insn.opcode() == IrOpcode.CONDITION) {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    BinaryOperator cmp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());
                    stmts.add(new ExpressionStatement(
                            new com.bingbaihanji.bdec.ast.expr.VarExpr(
                                    "/* if (" + left + " " + (cmp != null ? cmp : "?")
                                            + " " + right + ") */")));
                }
                continue;
            }

            // Skip INIT calls already merged into NEW
            if (currentInitToSkip.contains(insn.id())) {
                continue;
            }

            // Skip STORE instructions that have been inlined
            if (currentStoresToSkip.contains(insn.id())) {
                continue;
            }

            // Only emit statements for side-effecting instructions
            if (isStatementRoot(insn)) {
                Statement s = translateStmt(insn);
                if (s != null) {
                    stmts.add(s);
                }
            } else if (!consumed.contains(insn.id()) && insn.resultValue() != null) {
                // Standalone expression (result not consumed by anything) — still emit
                Expression e = translateExpr(insn);
                if (e != null && !isIgnorableExpr(e)) {
                    stmts.add(new ExpressionStatement(e));
                }
            }
        }

        if (stmts.isEmpty()) {
            return new BlockStatement(List.of());
        }

        // Post-pass: suppress bare "return;" after this()/super() constructor
        // delegation. In bytecode, constructors always end with RETURN, but
        // Java source doesn't need "return;" after a this()/super() call.
        if (!stmts.isEmpty()) {
            Statement last = stmts.get(stmts.size() - 1);
            if (last instanceof ReturnStatement r && r.value() == null) {
                boolean hasCtorDeleg = allInsns.stream().anyMatch(i ->
                        i.opcode() == IrOpcode.INVOKE
                                && (i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                                || i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)));
                if (hasCtorDeleg) {
                    stmts.remove(stmts.size() - 1);
                }
            }
        }

        if (stmts.size() == 1) {
            return stmts.getFirst();
        }
        return new BlockStatement(stmts);
    }

    private Statement translateStmt(IrInstruction insn) {
        return switch (insn.opcode()) {
            case RETURN -> {
                if (insn.operands().isEmpty()) {
                    yield new ReturnStatement(null);
                } else {
                    Expression retVal = valueToExpr(insn.operands().getFirst());
                    // Apply boolean folding from semantic annotations
                    retVal = applyBooleanAnnotation(insn, retVal);
                    // Also convert integer literal to boolean for boolean-return methods
                    // (needed for PHI-resolved values where annotation is skipped)
                    if (currentMethodReturnsBoolean
                            && retVal instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                            && lit.value() instanceof Integer i) {
                        retVal = new com.bingbaihanji.bdec.ast.expr.LitExpr(
                                i != 0, JavaType.BOOLEAN);
                    }
                    yield new ReturnStatement(retVal);
                }
            }
            case THROW -> new ThrowStatement(translateExpr(insn));
            case STORE, FIELD_STORE -> {
                Expression e = translateExpr(insn);
                yield e != null ? new ExpressionStatement(e) : null;
            }
            default -> {
                Expression e = translateExpr(insn);
                yield e != null ? new ExpressionStatement(e) : null;
            }
        };
    }

    /**
     * Translate a single IR instruction to an AST expression.
     * For intermediate values (LOAD, BINARY, etc.) this produces the
     * appropriate expression node that will be inlined into the parent statement.
     */
    private Expression translateExpr(IrInstruction insn) {
        return switch (insn.opcode()) {

            // Constants
            case CONST -> constToExpr(insn);

            // Variable load
            case LOAD -> {
                if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof Variable v) {
                    yield varToExpr(v);
                }
                yield new VarExpr("var");
            }

            // Variable store → assignment
            case STORE -> {
                Value target = insn.operands().getFirst();
                Value source = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                Expression lhs;
                if (target instanceof Variable v) {
                    lhs = varToExpr(v);
                } else {
                    lhs = valueToExpr(target);
                }
                Expression rhs = source != null ? valueToExpr(source) : new VarExpr("?");
                // Compound assignment detection: x = x OP y → x OP= y
                // When detected, use only the right operand (strip the duplicated left)
                BinaryOperator compoundOp = detectCompoundOp(lhs, rhs);
                Expression assignRhs = rhs;
                if (compoundOp != null && rhs instanceof BinExpr bin) {
                    assignRhs = bin.right(); // strip the duplicated left operand
                }
                // x += 1 → x++  /  x -= 1 → x--
                if (compoundOp != null && assignRhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                        && lr.value() instanceof Integer i && i == 1) {
                    if (compoundOp == BinaryOperator.ADD) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, lhs);
                    } else if (compoundOp == BinaryOperator.SUB) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, lhs);
                    }
                }
                // Increment/decrement detection: x = x + 1 → x++, x = x - 1 → x--
                if (compoundOp == null && rhs instanceof BinExpr bin
                        && expressionsMatch(lhs, bin.left())) {
                    UnaryOperator incOp = detectIncrement(bin);
                    if (incOp != null) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(incOp, lhs);
                    }
                }
                yield new AssignExpr(lhs, assignRhs, compoundOp);
            }

            // Field load — on implicit 'this' (slot 0, instance method), just emit field name
            case FIELD_LOAD -> {
                Expression obj = insn.operands().isEmpty() ? null : valueToExpr(insn.operands().getFirst());
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // In instance methods, field load on 'this' → just the field name
                if (isInstanceMethod && obj instanceof VarExpr v && "this".equals(v.name())) {
                    yield new VarExpr(fName);
                }
                yield new FieldAccessExpr(obj, fName);
            }

            // Field store → assignment to field
            case FIELD_STORE -> {
                Value obj = !insn.operands().isEmpty() ? insn.operands().getFirst() : null;
                Value val = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // Always use this.fieldName for instance field stores, so the output
                // clearly distinguishes field assignment from local variable assignment.
                // This prevents "capacity = x" when "this.capacity = x" was intended.
                Expression lhs;
                if (isInstanceMethod && obj instanceof Variable v && v.slot() == 0) {
                    lhs = new FieldAccessExpr(new VarExpr("this"), fName);
                } else if (obj instanceof Variable v) {
                    lhs = new FieldAccessExpr(varToExpr(v), fName);
                } else {
                    lhs = new FieldAccessExpr(null, fName);
                }
                Expression rhs = val != null ? valueToExpr(val) : new VarExpr("?");
                // Apply compound assignment and increment detection for field stores too
                BinaryOperator compoundOp = detectCompoundOp(lhs, rhs);
                Expression assignRhs = rhs;
                if (compoundOp != null && rhs instanceof BinExpr bin) {
                    assignRhs = bin.right();
                }
                // += 1 → ++, -= 1 → --
                if (compoundOp != null && assignRhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                        && lr.value() instanceof Integer i && i == 1) {
                    if (compoundOp == BinaryOperator.ADD) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, lhs);
                    } else if (compoundOp == BinaryOperator.SUB) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, lhs);
                    }
                }
                if (compoundOp == null && rhs instanceof BinExpr bin
                        && expressionsMatch(lhs, bin.left())) {
                    UnaryOperator incOp = detectIncrement(bin);
                    if (incOp != null) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(incOp, lhs);
                    }
                }
                yield new AssignExpr(lhs, assignRhs, compoundOp);
            }

            // Binary arithmetic — use original bytecode opcode to infer operator
            case BINARY -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    BinaryOperator binOp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());
                    yield new BinExpr(binOp != null ? binOp : BinaryOperator.ADD, left, right);
                }
                yield new VarExpr("/* binary */");
            }

            // Comparisons
            case COMPARE -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    yield new BinExpr(BinaryOperator.EQ, left, right);
                }
                yield new VarExpr("/* compare */");
            }

            // Condition — use original bytecode opcode to infer comparison operator.
            case CONDITION -> {
                if (insn.operands().size() >= 2) {
                    Value leftOp = insn.operands().get(0);
                    Value rightOp = insn.operands().get(1);

                    // Detect boolean variable compared to 0:
                    //   boolean == 0 → !boolean,  boolean != 0 → boolean
                    // This handles if(flag) vs if(!flag) from bytecode IFEQ/IFNE.
                    boolean leftIsBoolVar = leftOp instanceof Variable v
                            && v.type().kind() == com.bingbaihanji.bdec.type.TypeKind.BOOLEAN;
                    boolean rightIsBoolVar = rightOp instanceof Variable v
                            && v.type().kind() == com.bingbaihanji.bdec.type.TypeKind.BOOLEAN;
                    boolean rightIsZero = rightOp instanceof ConstantValue cv
                            && cv.value() instanceof Integer i && i == 0;
                    boolean leftIsZero = leftOp instanceof ConstantValue cv
                            && cv.value() instanceof Integer i && i == 0;

                    BinaryOperator cmpOp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());

                    if (leftIsBoolVar && rightIsZero) {
                        Expression varExpr = valueToExpr(leftOp);
                        if (cmpOp == BinaryOperator.EQ) {
                            // boolean == 0 → !boolean (IFEQ)
                            yield new UnExpr(UnaryOperator.NOT, varExpr);
                        } else if (cmpOp == BinaryOperator.NE) {
                            // boolean != 0 → boolean (IFNE)
                            yield varExpr;
                        }
                    }
                    if (rightIsBoolVar && leftIsZero) {
                        Expression varExpr = valueToExpr(rightOp);
                        if (cmpOp == BinaryOperator.EQ) {
                            yield new UnExpr(UnaryOperator.NOT, varExpr);
                        } else if (cmpOp == BinaryOperator.NE) {
                            yield varExpr;
                        }
                    }

                    // Detect COMPARE+CONDITION pattern
                    Value cmpVal = null;
                    if (rightOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE) {
                        cmpVal = rightOp;
                    } else if (leftOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE) {
                        cmpVal = leftOp;
                    }

                    if (cmpVal != null) {
                        IrInstruction cmp = ((InstructionRef) cmpVal).instruction();
                        if (cmp.operands().size() >= 2) {
                            Expression cmpLeft = valueToExpr(cmp.operands().get(0));
                            Expression cmpRight = valueToExpr(cmp.operands().get(1));
                            BinaryOperator cmpBinOp = IrInstruction.binaryOpFromBytecode(
                                    insn.originalOpcode());
                            if (cmpBinOp != null) {
                                yield new BinExpr(cmpBinOp, cmpLeft, cmpRight);
                            }
                        }
                    }

                    // Regular condition (no COMPARE merge)
                    Expression left = valueToExpr(leftOp);
                    Expression right = valueToExpr(rightOp);
                    yield new BinExpr(cmpOp != null ? cmpOp : BinaryOperator.EQ, left, right);
                }
                yield new VarExpr("/* condition */");
            }

            // Unary
            case UNARY -> {
                if (!insn.operands().isEmpty()) {
                    UnaryOperator uop = inferUnaryOp(insn.originalOpcode());
                    yield new UnExpr(uop, valueToExpr(insn.operands().getFirst()));
                }
                yield new VarExpr("/* unary */");
            }

            // Method invocation — first operand is receiver (for non-static calls)
            case INVOKE -> {
                List<Expression> args = new ArrayList<>();
                boolean isConstructor = insn.hasTag(
                        com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION)
                        || insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                        || insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR);

                String mName = insn.nameHint() != null ? insn.nameHint() : "method";

                // First operand is the receiver (if present — IrBuilder stores it as target)
                int argStart = 0;
                Expression target = null;
                if (isConstructor) {
                    // Constructor: first operand is 'this' (ALOAD_0) — skip it, use semantic name
                    argStart = 1;
                    target = null;
                    if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)) {
                        mName = "super";
                    } else if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)) {
                        mName = "this";
                    } else {
                        // Object creation: NEW + INVOKESPECIAL <init> pattern
                        // Replace "<init>" with the target class's simple name
                        var ann = insn.getAnnotation(
                                com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION);
                        if (ann != null) {
                            String targetClass = ann.getString(
                                    com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_TARGET_CLASS);
                            if (targetClass != null) {
                                int lastSlash = targetClass.lastIndexOf('/');
                                mName = lastSlash >= 0
                                        ? targetClass.substring(lastSlash + 1)
                                        : targetClass;
                            }
                        }
                    }
                } else if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                    // Static call with declaring class annotation → use class as target
                    var dcAnn = insn.getAnnotation(
                            com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS);
                    if (dcAnn != null) {
                        String dc = dcAnn.getString(
                                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                        if (dc != null) {
                            int lastSlash = dc.lastIndexOf('/');
                            target = new VarExpr(lastSlash >= 0
                                    ? dc.substring(lastSlash + 1) : dc);
                        }
                    }
                    argStart = 0; // all operands are args (no receiver)
                } else if (!insn.operands().isEmpty()) {
                    // Regular invoke: first operand is receiver → becomes target expression
                    Value firstOp = insn.operands().getFirst();
                    target = valueToExpr(firstOp);
                    argStart = 1;
                } else {
                    // Static call without annotation — no target
                    argStart = 0;
                }

                for (int i = argStart; i < insn.operands().size(); i++) {
                    args.add(valueToExpr(insn.operands().get(i)));
                }
                yield new InvocationExpr(target, mName, args, insn.resultType());
            }

            // Type cast
            case CAST -> {
                Expression operand = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("?");
                yield new CastExpr(insn.resultType(), operand);
            }

            // Object creation — with merged constructor args if folded
            case NEW -> {
                if (currentNewToInit.containsKey(insn.id())) {
                    List<IrInstruction> inits = currentNewToInit.get(insn.id());
                    List<Expression> ctorArgs = new ArrayList<>();
                    for (IrInstruction init : inits) {
                        for (int i = 0; i < init.operands().size(); i++) {
                            Value op = init.operands().get(i);
                            // Skip self-reference (receiver = this NEW instruction)
                            if (op instanceof InstructionRef ref
                                    && ref.instruction().id() == insn.id()) {
                                continue;
                            }
                            ctorArgs.add(valueToExpr(op));
                        }
                    }
                    // NewExpr constructor is (type, dimensions, constructorArgs)
                    yield new NewExpr(insn.resultType(), List.of(), ctorArgs);
                }
                // NewExpr constructor is (type, dimensions, constructorArgs)
                yield new NewExpr(insn.resultType(), List.of(), List.of());
            }
            case NEW_ARRAY -> {
                // Extract array size from operands (the stack value popped by NEWARRAY/ANEWARRAY)
                List<Expression> dims = new ArrayList<>();
                for (Value op : insn.operands()) {
                    dims.add(valueToExpr(op));
                }
                if (dims.isEmpty()) {
                    dims.add(new VarExpr("?"));
                }
                yield new NewExpr(insn.resultType(), dims, List.of());
            }

            // instanceof: nameHint carries the target class internal name
            case INSTANCE_OF -> {
                Expression obj = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("obj");
                JavaType checkedType = insn.nameHint() != null
                        ? JavaType.classType(insn.nameHint())
                        : JavaType.classType("java/lang/Object");
                yield new InstanceOfExpr(obj, checkedType);
            }

            // Array element load: a[i]
            case ARRAY_LOAD -> {
                Expression arr = insn.operands().size() > 0
                        ? valueToExpr(insn.operands().get(0)) : new VarExpr("arr");
                Expression idx = insn.operands().size() > 1
                        ? valueToExpr(insn.operands().get(1)) : new VarExpr("i");
                yield new ArrayAccessExpr(arr, idx);
            }
            // Array element store: a[i] = v
            case ARRAY_STORE -> {
                Expression arr = insn.operands().size() > 0
                        ? valueToExpr(insn.operands().get(0)) : new VarExpr("arr");
                Expression idx = insn.operands().size() > 1
                        ? valueToExpr(insn.operands().get(1)) : new VarExpr("i");
                Expression val = insn.operands().size() > 2
                        ? valueToExpr(insn.operands().get(2)) : new VarExpr("?");
                yield new AssignExpr(new ArrayAccessExpr(arr, idx), val);
            }

            // Array length
            case ARRAY_LENGTH -> {
                Expression arr = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("arr");
                yield new FieldAccessExpr(arr, "length");
            }

            // Increment (IINC) — operands: [readVar, writeVar, ConstantValue(incr)]
            case INC -> {
                if (insn.operands().size() >= 3 && insn.operands().getFirst() instanceof Variable v) {
                    Value incr = insn.operands().get(2); // index 2 is the increment value
                    VarExpr var = varToExpr(v);
                    Expression rhs = valueToExpr(incr);
                    // x += c → can become x++ if c == 1
                    if (rhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                            && lr.value() instanceof Integer i && i == 1) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, var);
                    }
                    yield new AssignExpr(var, new BinExpr(BinaryOperator.ADD, var, rhs));
                }
                yield new VarExpr("/* inc */");
            }

            // Throw
            case THROW -> !insn.operands().isEmpty() ? valueToExpr(insn.operands().getFirst()) : new VarExpr("ex");

            // PHI — pick the operand belonging to the current branch context.
            // If we know which blocks are being translated (branchBlocks hint),
            // pick the PHI operand whose defining instruction is in those blocks.
            // Otherwise pick the first non-trivial operand.
            case PHI -> {
                Expression resolved = null;
                if (currentBranchBlocks != null) {
                    for (Value op : insn.operands()) {
                        if (op instanceof InstructionRef ref
                                && currentBranchBlocks.contains(ref.instruction().blockId())) {
                            resolved = translateExpr(ref.instruction());
                            break;
                        }
                    }
                }
                if (resolved == null) {
                    for (Value op : insn.operands()) {
                        if (op instanceof InstructionRef ref) {
                            resolved = translateExpr(ref.instruction());
                            break;
                        }
                        if (op instanceof ConstantValue cv) {
                            resolved = new LitExpr(cv.value(), cv.type());
                            break;
                        }
                        if (op instanceof Variable v) {
                            resolved = varToExpr(v);
                            break;
                        }
                    }
                }
                yield resolved != null ? resolved : new VarExpr("merge" + insn.id());
            }

            default -> new VarExpr("/* " + insn.opcode() + " */");
        };
    }

    /** Convert a Value (Variable / ConstantValue / InstructionRef) to an Expression.
     *  For InstructionRef, recursively translates the referenced instruction
     *  to build proper expression trees. */
    private Expression valueToExpr(Value v) {
        return switch (v) {
            case Variable var -> {
                // Check if this variable's value was inlined from a STORE.
                // This handles: x = 42; ... use(x) → 42
                Value storeSource = currentVarStoreSource.get(var);
                if (storeSource != null) {
                    yield valueToExpr(storeSource);
                }
                yield varToExpr(var);
            }
            case ConstantValue cv -> {
                Object val = cv.value();
                if (val == null) {
                    yield new VarExpr("null");
                }
                yield new LitExpr(val, cv.type());
            }
            case InstructionRef ref -> {
                // Recursively translate the referenced instruction to build expression tree
                IrInstruction def = ref.instruction();
                Expression expr = translateExpr(def);
                yield expr != null ? expr : new VarExpr("tmp" + def.id());
            }
            default -> new VarExpr("?");
        };
    }

    /** Detect compound assignment pattern: {@code x = x OP y} → {@code x OP= y}.
     *  Returns the operator if the pattern matches, null for plain assignment. */
    private BinaryOperator detectCompoundOp(Expression lhs, Expression rhs) {
        if (!(rhs instanceof BinExpr bin)) {
            return null;
        }
        // Match: lhs matches the left operand of the binary expression
        if (expressionsMatch(lhs, bin.left())) {
            return bin.operator();
        }
        return null;
    }

    /** Check if two expressions are structurally equivalent (same variable/field).
     *  Handles the equivalence: {@code VarExpr("size") ≈ FieldAccessExpr(this, "size")}
     *  which arises because {@code FIELD_LOAD on this} emits bare field names. */
    private boolean expressionsMatch(Expression a, Expression b) {
        if (a instanceof VarExpr va && b instanceof VarExpr vb) {
            return va.name().equals(vb.name());
        }
        if (a instanceof FieldAccessExpr fa && b instanceof FieldAccessExpr fb) {
            return fa.fieldName().equals(fb.fieldName())
                    && (fa.target() == null && fb.target() == null
                    || (fa.target() != null && fb.target() != null
                    && expressionsMatch(fa.target(), fb.target())));
        }
        // Cross-type: VarExpr("size") matches FieldAccessExpr(this, "size")
        if (a instanceof VarExpr va && b instanceof FieldAccessExpr fb) {
            return fb.target() instanceof VarExpr t && "this".equals(t.name())
                    && va.name().equals(fb.fieldName());
        }
        if (b instanceof VarExpr vb && a instanceof FieldAccessExpr fa) {
            return fa.target() instanceof VarExpr t && "this".equals(t.name())
                    && vb.name().equals(fa.fieldName());
        }
        return false;
    }

    /** Convert a Variable to the appropriate VarExpr.
     *  Uses the variable's name (from LocalVariableTable if available,
     *  falling back to "var" + originalIndex). Versioned variables
     *  that represent slot-0 temps are distinguished from {@code this}. */
    private VarExpr varToExpr(Variable var) {
        String name = var.name();
        // In instance methods, slot 0 version 0 is 'this'
        if (isInstanceMethod && var.slot() == 0 && var.version() == 0) {
            return new VarExpr("this");
        }
        // For LVT-named variables, always use the name
        if (name != null && !name.startsWith("var")) {
            return new VarExpr(name);
        }
        // Fallback: distinguish versions for same-slot variables
        if (var.version() > 0) {
            return new VarExpr("var" + var.slot());
        }
        return new VarExpr(name != null ? name : "var" + var.slot());
    }

    /** Convert CONST IR to a LitExpr. */
    private Expression constToExpr(IrInstruction insn) {
        if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof ConstantValue cv) {
            Object v = cv.value();
            if (v instanceof String s) {
                return new LitExpr(s, JavaType.classType("java/lang/String"));
            }
            // Check for boolean annotation from TypeAwareConstantFolder
            if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN)) {
                var ann = insn.getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN);
                if (ann != null) {
                    return new LitExpr(ann.getBoolean(
                            com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_BOOLEAN_VALUE),
                            JavaType.BOOLEAN);
                }
            }
            // Preserve the type from ConstantValue for proper emission
            return new LitExpr(v != null ? v : "null", cv.type());
        }
        return new VarExpr("/* const */");
    }

    /** Map bytecode opcode to UnaryOperator for UNARY IR instructions. */
    private UnaryOperator inferUnaryOp(int bc) {
        return switch (bc) {
            case 0x74, 0x75, 0x76, 0x77 -> UnaryOperator.NEG; // INEG, LNEG, FNEG, DNEG
            default -> UnaryOperator.NEG;
        };
    }

    /** Build a SwitchStatement from switch info and the grouped blocks. */
    private SwitchStatement buildSwitch(SwitchInfo info, BlockGroup group, LinearIr ir,
                                        List<BlockGroup> allGroups, Set<BlockGroup> consumed) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        Expression discriminant = new VarExpr("switchKey");
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.SWITCH && !insn.operands().isEmpty()) {
                discriminant = valueToExpr(insn.operands().getFirst());
                break;
            }
        }

        // Collect all case target blocks so we can consume their groups
        Set<BasicBlock> allCaseBlocks = new HashSet<>();
        info.caseBodies().values().forEach(allCaseBlocks::addAll);
        allCaseBlocks.addAll(info.defaultBody());

        // Consume groups containing case target blocks
        for (BlockGroup g : allGroups) {
            if (consumed.contains(g)) {
                continue;
            }
            for (BasicBlock gb : g.blocks()) {
                if (allCaseBlocks.contains(gb)) {
                    consumed.add(g);
                    break;
                }
            }
        }

        List<SwitchStatement.CaseGroup> caseGroups = new ArrayList<>();
        for (var entry : info.caseBodies().entrySet()) {
            List<Expression> labels = List.of(
                    new LitExpr(entry.getKey(), JavaType.INT));
            List<Statement> body = new ArrayList<>();
            for (BasicBlock b : entry.getValue()) {
                body.addAll(translateBlockGroup(new BlockGroup(b), ir));
            }
            caseGroups.add(new SwitchStatement.CaseGroup(labels, body, false));
        }
        if (!info.defaultBody().isEmpty()) {
            List<Statement> defBody = new ArrayList<>();
            for (BasicBlock b : info.defaultBody()) {
                defBody.addAll(translateBlockGroup(new BlockGroup(b), ir));
            }
            caseGroups.add(new SwitchStatement.CaseGroup(List.of(), defBody, true));
        }

        return new SwitchStatement(discriminant, caseGroups);
    }

    /**
     * If the instruction has a BOOLEAN_RETURN annotation and the expression
     * is a numeric LitExpr, replace it with a boolean LitExpr.
     */
    private Expression applyBooleanAnnotation(IrInstruction insn, Expression expr) {
        if (!insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN)) {
            return expr;
        }
        // Don't override if the value comes from a PHI — branch context
        // resolution already picks the correct per-branch value.
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

    /** Check if any IR instruction in the group has a SYNCHRONIZED_BLOCK tag.
     *  This is more reliable than matching emitted placeholder text. */
    private boolean groupHasSynchronizedAnnotation(BlockGroup group, LinearIr ir) {
        return group.allIrInstructions(ir).stream().anyMatch(
                i -> i.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK)
                        && i.opcode() == IrOpcode.MONITOR_ENTER);
    }

    /** Check if a statement tree contains synchronized block annotations.
     *  Fallback detection via IR-level tag checking. */
    private boolean isSynchronizedBlock(Statement stmt) {
        return false; // detection now uses IR-level tags in groupHasSynchronizedAnnotation
    }

    /** Wrap a statement tree as a synchronized block. */
    private SynchronizedStatement wrapSynchronized(Statement body,
                                                   BlockGroup group, LinearIr ir) {
        // Find the monitor object from MONITOR_ENTER annotation
        String monitorObj = "obj";
        for (IrInstruction insn : group.allIrInstructions(ir)) {
            if (insn.opcode() == IrOpcode.MONITOR_ENTER
                    && insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK)) {
                var ann = insn.getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag.SYNCHRONIZED_BLOCK);
                if (ann != null) {
                    String desc = ann.getString(com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_MONITOR_OBJECT);
                    if (desc != null) {
                        monitorObj = desc;
                    }
                }
                break;
            }
        }

        // Filter out monitor enter/exit instructions from body
        if (body instanceof BlockStatement bs) {
            List<Statement> filtered = new ArrayList<>();
            for (Statement s : bs.statements()) {
                if (s instanceof ExpressionStatement es) {
                    if (es.expression() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                            && ("/* monitor enter */".equals(v.name())
                            || "/* monitor exit */".equals(v.name()))) {
                        continue;
                    }
                }
                filtered.add(s);
            }
            body = new BlockStatement(filtered);
        }

        return new SynchronizedStatement(
                new com.bingbaihanji.bdec.ast.expr.VarExpr(monitorObj), body);
    }

    /** Build a TryStatement from try-catch info.
     *  Detects finally blocks: when the handler is catch-all (null or Throwable)
     *  and ends with THROW (the re-throw pattern), extract the handler body
     *  minus the throw as a finally block. */
    private TryStatement buildTryCatch(TryCatchInfo info, Statement tryBody, LinearIr ir) {
        boolean isCatchAll = info.catchType() == null
                || "java/lang/Throwable".equals(info.catchType());

        // Get the handler block's instructions
        List<IrInstruction> handlerInsns = ir.instructionsOf(info.handlerBlock());

        // Check if this is a finally pattern: catch-all + ends with THROW
        boolean isFinally = isCatchAll && !handlerInsns.isEmpty()
                && handlerInsns.getLast().opcode() == IrOpcode.THROW;

        if (isFinally) {
            // Extract finally body: the handler instructions minus the final THROW
            List<IrInstruction> finallyInsns = handlerInsns.subList(0, handlerInsns.size() - 1);
            BlockGroup finallyGroup = new BlockGroup(info.handlerBlock());
            Statement finallyBody = translateGroup(finallyGroup, ir);

            // Filter out the THROW from the emitted statements.
            if (finallyBody instanceof BlockStatement bs) {
                List<Statement> stmts = new ArrayList<>();
                for (Statement s : bs.statements()) {
                    if (s instanceof ThrowStatement) {
                        continue;
                    }
                    if (s instanceof ExpressionStatement es
                            && es.expression() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                            && "/* throw */".equals(v.name())) {
                        continue;
                    }
                    stmts.add(s);
                }
                finallyBody = new BlockStatement(stmts);
            }

            // Strip duplicated finally-body statements from the try body.
            // Bytecode duplicates finally code: once in the normal exit path
            // (which gets grouped into the try body) and once in the handler.
            // We want the finally code ONLY in the finally block.
            tryBody = stripDuplicatedFinally(tryBody, finallyBody);

            return new TryStatement(tryBody, List.of(), finallyBody);
        }

        // Regular catch clause
        List<TryStatement.CatchClause> catchClauses = new ArrayList<>();
        String excType = info.catchType();
        if (excType != null && excType.contains("/")) {
            excType = excType.substring(excType.lastIndexOf('/') + 1);
        }
        // Translate handler instructions as the catch body
        BlockGroup handlerGroup = new BlockGroup(info.handlerBlock());
        Statement handlerBody = translateGroup(handlerGroup, ir);
        catchClauses.add(new TryStatement.CatchClause(
                excType != null ? excType : "Exception",
                "e",
                handlerBody));
        return new TryStatement(tryBody, catchClauses, null);
    }

    /**
     * Strip statements from the try body that also appear in the finally body.
     * Uses structural comparison on the Expression objects rather than toString().
     */
    private Statement stripDuplicatedFinally(Statement tryBody, Statement finallyBody) {
        List<Statement> finallyStmts = collectStatements(finallyBody);
        if (finallyStmts.isEmpty()) {
            return tryBody;
        }

        List<Statement> tryStmts = collectStatements(tryBody);
        List<Statement> filtered = new ArrayList<>();
        for (Statement s : tryStmts) {
            if (!matchesAny(s, finallyStmts)) {
                filtered.add(s);
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

    /** Translate a single block group to a list of statements. */
    private List<Statement> translateBlockGroup(BlockGroup group, LinearIr ir) {
        return translateGroup(group, ir) instanceof BlockStatement bs
                ? bs.statements()
                : List.of();
    }

    /**
     * Build a global (cross-group) map from Variable → stored Value for
     * single-use variables. This allows inlining constants across group
     * boundaries, e.g., STORE in the try-body group and LOAD in the normal-exit
     * group (common in try-finally patterns).
     */
    private void buildGlobalVarInlineMap(List<BlockGroup> groups, LinearIr ir) {
        Map<Variable, Value> varStoreSource = new HashMap<>();
        Set<Integer> storesToSkip = new HashSet<>();

        // Collect all instructions across all groups
        List<IrInstruction> allInsns = new ArrayList<>();
        for (BlockGroup g : groups) {
            allInsns.addAll(g.allIrInstructions(ir));
        }

        // First pass: count how many times each Variable is referenced
        // (both via LOAD and directly in other instruction operands like RETURN).
        Map<Variable, Integer> varUseCount = new HashMap<>();
        Map<Variable, Integer> loadIdForVar = new HashMap<>();
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.LOAD && !insn.operands().isEmpty()
                    && insn.operands().getFirst() instanceof Variable v) {
                varUseCount.merge(v, 1, Integer::sum);
                loadIdForVar.put(v, insn.id());
            }
            // Also count direct Variable references (e.g., RETURN operand)
            for (Value op : insn.operands()) {
                if (op instanceof Variable v && insn.opcode() != IrOpcode.STORE
                        && insn.opcode() != IrOpcode.LOAD) {
                    varUseCount.merge(v, 1, Integer::sum);
                }
            }
        }

        // Build consumed set (InstructionRef usage + direct Variable usage)
        Set<Integer> consumedInsnIds = new HashSet<>();
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumedInsnIds.add(ref.instruction().id());
                }
            }
        }

        // Second pass: track stores with single-use variables
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.STORE && insn.operands().size() >= 2
                    && insn.operands().get(0) instanceof Variable v) {
                Value source = insn.operands().get(1);
                int useCount = varUseCount.getOrDefault(v, 0);
                if (useCount == 1 && isSimpleValue(source)) {
                    // Check if the LOAD (if any) is consumed, OR if the variable
                    // is used directly (e.g., RETURN operand).
                    Integer loadId = loadIdForVar.get(v);
                    boolean canInline;
                    if (loadId != null) {
                        // Variable is loaded via LOAD — check LOAD is consumed
                        canInline = consumedInsnIds.contains(loadId);
                    } else {
                        // Variable is used directly — always safe to inline
                        // (the variable itself is the use site)
                        canInline = true;
                    }
                    if (canInline) {
                        varStoreSource.put(v, source);
                        storesToSkip.add(insn.id());
                    }
                }
            }
        }

        currentVarStoreSource = Map.copyOf(varStoreSource);
        currentStoresToSkip = Set.copyOf(storesToSkip);
    }

    // ── BlockGroup helper ─────────────────────────────────────────────

    private static class BlockGroup {

        private final List<BasicBlock> blocks = new ArrayList<>();

        BlockGroup(BasicBlock first) {blocks.add(first);}

        void add(BasicBlock b) {blocks.add(b);}

        BasicBlock first() {return blocks.getFirst();}

        BasicBlock last() {return blocks.getLast();}

        List<BasicBlock> blocks() {return blocks;}

        List<IrInstruction> allIrInstructions(LinearIr ir) {
            List<IrInstruction> result = new ArrayList<>();
            for (BasicBlock b : blocks) {
                result.addAll(ir.instructionsOf(b));
            }
            return result;
        }
    }
}
