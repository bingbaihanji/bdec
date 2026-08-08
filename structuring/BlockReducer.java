package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        if (tryCatchAnns.isEmpty()) return root;

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
                    if (firstTryGroup < 0) firstTryGroup = i;
                    lastTryGroup = i;
                }
            }

            // Wrap the contiguous range of try-group statements
            if (firstTryGroup >= 0 && lastTryGroup >= firstTryGroup
                    && firstTryGroup < stmts.size()) {
                List<Statement> tryBodyStmts = new ArrayList<>();
                for (int i = firstTryGroup; i <= lastTryGroup && i < stmts.size(); i++) {
                    tryBodyStmts.add(stmts.get(i));
                }
                // Replace the first statement with wrapped version, mark rest for removal
                if (!tryBodyStmts.isEmpty()) {
                    Statement tryBody = tryBodyStmts.size() == 1
                            ? tryBodyStmts.get(0)
                            : new BlockStatement(tryBodyStmts);
                    stmts.set(firstTryGroup, buildTryCatch(tci, tryBody, ir));
                    // Remove subsequent try-group statements (they've been absorbed)
                    for (int i = lastTryGroup; i > firstTryGroup; i--) {
                        if (i < stmts.size()) stmts.remove(i);
                    }
                }
            }
        }

        return new BlockStatement(stmts);
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

    // ── Block grouping ────────────────────────────────────────────────

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

        List<BlockGroup> groups = groupAdjacentBlocks(sorted, graph);
        Set<BlockGroup> consumed = new HashSet<>();
        List<Statement> statements = new ArrayList<>();

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
            // directly from CFG structure (condition block with 2 successors)
            if (ifInfo == null) {
                ifInfo = detectIfHeader(group, graph, ir);
            }

            Statement s;

            // if-else: build proper IfStatement with both then and else bodies
            if (ifInfo != null) {
                Expression cond = extractCondition(group, ir);

                // Translate then-body: find the group(s) containing then-blocks
                Statement thenBody = translateBranchBody(ifInfo.thenBlocks(), groups, ir, consumed);

                // Translate else-body: find the group(s) containing else-blocks
                Statement elseBody = null;
                if (!ifInfo.elseBlocks().isEmpty()) {
                    elseBody = translateBranchBody(ifInfo.elseBlocks(), groups, ir, consumed);
                }

                s = new IfStatement(cond != null ? cond : new VarExpr("/*condition*/"),
                        thenBody != null ? thenBody : new BlockStatement(List.of()),
                        elseBody);
            }
            // loop: wrap group in LoopStatement (only if we have a valid body)
            else if (loopInfo != null) {
                s = translateGroup(group, ir);
                if (s != null && !isEmptyBlock(s)) {
                    Expression cond = extractCondition(group, ir);
                    s = new LoopStatement(LoopStatement.LoopKind.WHILE,
                            cond != null ? cond : new VarExpr("true"), s);
                }
            }
            // switch
            else if (switchInfo != null) {
                s = buildSwitch(switchInfo, group, ir);
            }
            // try-catch: defer wrapping to post-processing pass (wrapTryCatchBlocks)
            // This ensures try ranges that contain if/else/loop structures
            // are properly wrapped AFTER inner structures are built.
            else if (tryCatchInfo != null) {
                s = translateGroup(group, ir);
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
        // Post-process: wrap statement groups in try-catch based on annotations
        return wrapTryCatchBlocks(new BlockStatement(statements), groups, tryCatchAnns, ir);
    }

    /** Find if any block in the group has an IfInfo annotation. */
    private IfInfo findIfAnnotation(BlockGroup group, Map<BasicBlock, IfInfo> ifAnns) {
        for (BasicBlock b : group.blocks()) {
            if (ifAnns.containsKey(b)) return ifAnns.get(b);
        }
        return null;
    }

    private LoopInfo findLoopAnnotation(BlockGroup group, Map<BasicBlock, LoopInfo> loopAnns) {
        for (BasicBlock b : group.blocks()) {
            if (loopAnns.containsKey(b)) return loopAnns.get(b);
        }
        return null;
    }

    private TryCatchInfo findTryAnnotation(BlockGroup group, Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        for (BasicBlock b : group.blocks()) {
            if (tryCatchAnns.containsKey(b)) return tryCatchAnns.get(b);
        }
        return null;
    }

    private SwitchInfo findSwitchAnnotation(BlockGroup group, Map<BasicBlock, SwitchInfo> switchAnns) {
        for (BasicBlock b : group.blocks()) {
            if (switchAnns.containsKey(b)) return switchAnns.get(b);
        }
        return null;
    }

    /**
     * Detect if-header directly from CFG structure, bypassing BranchAnalyzer.
     * Checks: the group's last block has a CONDITION instruction and exactly
     * 2 outgoing TRUE_BRANCH/FALSE_BRANCH edges.
     */
    private IfInfo detectIfHeader(BlockGroup group, ControlFlowGraph graph, LinearIr ir) {
        for (BasicBlock b : group.blocks()) {
            // Check if any instruction in this block is a CONDITION
            boolean hasCondition = ir.instructionsOf(b).stream()
                    .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
            if (!hasCondition) continue;

            // Check if block has exactly 2 successors
            List<BasicBlock> succs = graph.successorsOf(b);
            if (succs.size() != 2) continue;

            // Check if the outgoing edges are TRUE_BRANCH/FALSE_BRANCH
            boolean hasCondEdges = graph.outgoingOf(b).stream()
                    .anyMatch(e -> e.kind() == EdgeKind.TRUE_BRANCH
                            || e.kind() == EdgeKind.FALSE_BRANCH);
            if (!hasCondEdges) continue;

            // Build IfInfo: first succ = then, second = else
            BasicBlock follow = graph.exitBlock();
            Set<BasicBlock> thenBlocks = collectReachableBlocks(succs.get(0), follow, graph);
            Set<BasicBlock> elseBlocks = collectReachableBlocks(succs.get(1), follow, graph);
            return new IfInfo(b, follow, thenBlocks, elseBlocks);
        }
        return null;
    }

    /** Collect all blocks reachable from start up to (but not including) stop. */
    private Set<BasicBlock> collectReachableBlocks(BasicBlock start, BasicBlock stop,
                                                    ControlFlowGraph graph) {
        Set<BasicBlock> result = new LinkedHashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock curr = queue.poll();
            if (curr == stop || !result.add(curr)) continue;
            for (BasicBlock succ : graph.successorsOf(curr)) {
                if (succ != stop) queue.add(succ);
            }
        }
        return result;
    }

    /**
     * Translate the blocks belonging to one branch (then or else) of an if-statement.
     * Consumes the matching groups so they aren't emitted again.
     */
    private Statement translateBranchBody(Set<BasicBlock> branchBlocks,
                                          List<BlockGroup> allGroups,
                                          LinearIr ir,
                                          Set<BlockGroup> consumed) {
        List<Statement> bodyStmts = new ArrayList<>();
        for (BlockGroup g : allGroups) {
            if (consumed.contains(g)) {
                continue;
            }
            // If this group's first block is part of the branch body, consume it
            if (branchBlocks.contains(g.first())) {
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
        if (bodyStmts.size() == 1) {
            return bodyStmts.getFirst();
        }
        return new BlockStatement(bodyStmts);
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

    // ── Group → Statement translation ──────────────────────────────

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

    private boolean isAdjacent(BasicBlock prev, BasicBlock next, ControlFlowGraph graph) {
        List<BasicBlock> succs = graph.successorsOf(prev);
        if (succs.size() != 1 || succs.get(0) != next) {
            return false;
        }
        return graph.outgoingOf(prev).stream().allMatch(e -> e.kind() == EdgeKind.FALL_THROUGH);
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

        // Build index: which instruction IDs have their results consumed
        Set<Integer> consumed = new HashSet<>();
        for (IrInstruction insn : allInsns) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    consumed.add(ref.instruction().id());
                }
            }
        }

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
            // Skip conditions entirely — they are extracted by IfStatement/LoopStatement
            // wrappers via extractCondition() in reduce(). Never emit them as standalone
            // ExpressionStatements (that would put the condition inside the if-body).
            if (insn.opcode() == IrOpcode.CONDITION) {
                continue;
            }

            // Skip INIT calls already merged into NEW
            if (currentInitToSkip.contains(insn.id())) {
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
        if (stmts.size() == 1) {
            return stmts.getFirst();
        }
        return new BlockStatement(stmts);
    }

    // ── IR → Statement ─────────────────────────────────────────────

    private Statement translateStmt(IrInstruction insn) {
        return switch (insn.opcode()) {
            case RETURN -> {
                if (insn.operands().isEmpty()) {
                    yield new ReturnStatement(null);
                } else {
                    Expression retVal = valueToExpr(insn.operands().getFirst());
                    // Apply boolean folding from semantic annotations
                    retVal = applyBooleanAnnotation(insn, retVal);
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

    // ── IR → Expression ────────────────────────────────────────────

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
                    lhs = new VarExpr("var" + v.slot());
                } else {
                    lhs = valueToExpr(target);
                }
                Expression rhs = source != null ? valueToExpr(source) : new VarExpr("?");
                yield new AssignExpr(lhs, rhs);
            }

            // Field load — on implicit 'this' (slot 0, instance method), just emit field name
            case FIELD_LOAD -> {
                Expression obj = insn.operands().isEmpty() ? null : valueToExpr(insn.operands().getFirst());
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // In instance methods, field load on var0 (this) → just the field name
                if (isInstanceMethod && obj instanceof VarExpr v && "var0".equals(v.name())) {
                    yield new VarExpr(fName);
                }
                yield new FieldAccessExpr(obj, fName);
            }

            // Field store → assignment to field
            case FIELD_STORE -> {
                Value obj = !insn.operands().isEmpty() ? insn.operands().getFirst() : null;
                Value val = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // In instance methods, field store on var0 (this) → just the field name
                Expression lhs;
                if (isInstanceMethod && obj instanceof Variable v && v.slot() == 0) {
                    lhs = new VarExpr(fName);
                } else if (obj instanceof Variable v) {
                    lhs = new FieldAccessExpr(new VarExpr("var" + v.slot()), fName);
                } else {
                    lhs = new FieldAccessExpr(null, fName);
                }
                Expression rhs = val != null ? valueToExpr(val) : new VarExpr("?");
                yield new AssignExpr(lhs, rhs);
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

            // Condition — use original bytecode opcode to infer comparison operator
            case CONDITION -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    BinaryOperator cmpOp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());
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
            case NEW_ARRAY -> new NewExpr(insn.resultType(), List.of(new VarExpr("?")), List.of());

            // instanceof
            case INSTANCE_OF -> new VarExpr("/* instanceof */");

            // Array length
            case ARRAY_LENGTH -> {
                Expression arr = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("arr");
                yield new FieldAccessExpr(arr, "length");
            }

            // Increment (IINC)
            case INC -> {
                if (insn.operands().size() >= 2 && insn.operands().getFirst() instanceof Variable v) {
                    Value incr = insn.operands().get(1);
                    VarExpr var = varToExpr(v);
                    Expression rhs = valueToExpr(incr);
                    yield new AssignExpr(var, new BinExpr(BinaryOperator.ADD, var, rhs));
                }
                yield new VarExpr("/* inc */");
            }

            // Throw
            case THROW -> !insn.operands().isEmpty() ? valueToExpr(insn.operands().getFirst()) : new VarExpr("ex");

            // PHI — not handled at expression level during structuring
            case PHI -> new VarExpr("phi");

            default -> new VarExpr("/* " + insn.opcode() + " */");
        };
    }

    /** Convert a Value (Variable / ConstantValue / InstructionRef) to an Expression.
     *  For InstructionRef, recursively translates the referenced instruction
     *  to build proper expression trees. */
    private Expression valueToExpr(Value v) {
        return switch (v) {
            case Variable var -> varToExpr(var);
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

    /** Convert a Variable to the appropriate VarExpr.
     *  In instance methods, slot 0 that represents {@code this} is never written to —
     *  if we detect slot 0 as a store target, it's actually a local temp.
     *  We keep "var0" naming to avoid confusing assignments like {@code this = ...}. */
    private VarExpr varToExpr(Variable var) {
        return new VarExpr("var" + var.slot());
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
    private SwitchStatement buildSwitch(SwitchInfo info, BlockGroup group, LinearIr ir) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        Expression discriminant = new VarExpr("switchKey");
        for (IrInstruction insn : allInsns) {
            if (insn.opcode() == IrOpcode.SWITCH && !insn.operands().isEmpty()) {
                discriminant = valueToExpr(insn.operands().getFirst());
                break;
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
        var ann = insn.getAnnotation(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN);
        if (ann == null) {
            return expr;
        }

        boolean boolVal = ann.getBoolean(
                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_BOOLEAN_VALUE);
        return new LitExpr(boolVal,
                com.bingbaihanji.bdec.type.JavaType.BOOLEAN);
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

            // Filter out the THROW from the emitted statements
            if (finallyBody instanceof BlockStatement bs) {
                List<Statement> stmts = new ArrayList<>();
                for (Statement s : bs.statements()) {
                    if (s instanceof ExpressionStatement es
                            && es.expression() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                            && "/* throw */".equals(v.name())) {
                        continue;
                    }
                    stmts.add(s);
                }
                finallyBody = new BlockStatement(stmts);
            }

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

    /** Translate a single block group to a list of statements. */
    private List<Statement> translateBlockGroup(BlockGroup group, LinearIr ir) {
        return translateGroup(group, ir) instanceof BlockStatement bs
                ? bs.statements()
                : List.of();
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
