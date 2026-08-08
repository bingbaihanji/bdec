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
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Converts a structured CFG into AST statements by translating
 * {@link IrInstruction} objects into proper AST expression/statement nodes.
 */
public final class BlockReducer {

    public BlockStatement reduce(ControlFlowGraph graph, LinearIr ir,
                                 Map<BasicBlock, LoopInfo> loopAnns,
                                 Map<BasicBlock, IfInfo> ifAnns,
                                 Map<BasicBlock, SwitchInfo> switchAnns,
                                 Map<BasicBlock, TryCatchInfo> tryCatchAnns) {
        List<BasicBlock> sorted = new ArrayList<>();
        for (BasicBlock b : graph.blocks()) {
            if (b != graph.entryBlock() && b != graph.exitBlock() && !b.instructions().isEmpty()) {
                sorted.add(b);
            }
        }
        sorted.sort(Comparator.comparingInt(BasicBlock::startOffset));

        List<BlockGroup> groups = groupAdjacentBlocks(sorted, graph);
        List<Statement> statements = new ArrayList<>();
        for (BlockGroup group : groups) {
            Statement s = translateGroup(group, ir);
            if (s == null) {
                continue;
            }

            // Wrap in structured AST if this block is a loop/if/switch/try header
            BasicBlock header = group.first();
            if (loopAnns.containsKey(header)) {
                Expression cond = extractCondition(group, ir);
                s = new LoopStatement(LoopStatement.LoopKind.WHILE, cond, s);
            } else if (ifAnns.containsKey(header)) {
                Expression cond = extractCondition(group, ir);
                s = new IfStatement(cond != null ? cond : new VarExpr("/*condition*/"),
                        s, null);
            } else if (switchAnns.containsKey(header)) {
                s = buildSwitch(switchAnns.get(header), group, ir);
            } else if (tryCatchAnns.containsKey(header)) {
                s = buildTryCatch(tryCatchAnns.get(header), s);
            }
            statements.add(s);
        }
        return new BlockStatement(statements);
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

    // ── Block grouping ────────────────────────────────────────────────

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

    // ── Group → Statement translation ──────────────────────────────

    private Statement translateGroup(BlockGroup group, LinearIr ir) {
        List<IrInstruction> allInsns = group.allIrInstructions(ir);
        if (allInsns.isEmpty()) {
            return null;
        }

        List<Statement> stmts = new ArrayList<>();
        for (IrInstruction insn : allInsns) {
            // Skip conditions — they're used by IfStatement/LoopStatement wrappers
            if (insn.opcode() == IrOpcode.CONDITION) {
                continue;
            }
            Statement s = translateStmt(insn);
            if (s != null) {
                stmts.add(s);
            }
        }

        if (stmts.isEmpty()) {
            return new ExpressionStatement(new VarExpr("// (empty)"));
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
                    yield new ReturnStatement(valueToExpr(insn.operands().getFirst()));
                }
            }
            case THROW -> new ExpressionStatement(translateExpr(insn));
            case STORE, FIELD_STORE -> new ExpressionStatement(translateExpr(insn));
            default -> {
                Expression e = translateExpr(insn);
                yield e != null ? new ExpressionStatement(e) : null;
            }
        };
    }

    // ── IR → Expression ────────────────────────────────────────────

    /**
     * Translate a single IR instruction to an AST expression.
     * This is the core of the translation — mapping IR ops to AST nodes.
     */
    private Expression translateExpr(IrInstruction insn) {
        return switch (insn.opcode()) {

            // Constants
            case CONST -> constToExpr(insn);

            // Variable load
            case LOAD -> {
                if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof Variable v) {
                    yield new VarExpr("var" + v.slot());
                }
                yield new VarExpr("var");
            }

            // Variable store → assignment
            case STORE -> {
                Value target = insn.operands().getFirst();
                Value source = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                Expression lhs = valueToExpr(target);
                Expression rhs = source != null ? valueToExpr(source) : new VarExpr("?");
                yield new AssignExpr(lhs, rhs);
            }

            // Field load
            case FIELD_LOAD -> {
                Expression obj = insn.operands().isEmpty() ? null : valueToExpr(insn.operands().getFirst());
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                yield new FieldAccessExpr(obj, fName);
            }

            // Field store → assignment to field
            case FIELD_STORE -> {
                Value obj = !insn.operands().isEmpty() ? insn.operands().getFirst() : null;
                Value val = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                Expression lhs = obj instanceof Variable
                        ? new FieldAccessExpr(new VarExpr("var" + ((Variable) obj).slot()), fName)
                        : new FieldAccessExpr(null, fName);
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

            // Comparisons — use original bytecode opcode to infer operator
            case COMPARE -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    // LCMP/FCMP/DCMP produce int signum; compare with 0
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

            // Unary negation — bytecode distinguishes NEG from NOT
            case UNARY -> {
                if (!insn.operands().isEmpty()) {
                    UnaryOperator uop = inferUnaryOp(insn.originalOpcode());
                    yield new UnExpr(uop, valueToExpr(insn.operands().getFirst()));
                }
                yield new VarExpr("/* unary */");
            }

            // Method invocation — use resolved name from constant pool
            case INVOKE -> {
                List<Expression> args = new ArrayList<>();
                int start = 0;
                for (int i = start; i < insn.operands().size(); i++) {
                    args.add(valueToExpr(insn.operands().get(i)));
                }
                String mName = insn.nameHint() != null ? insn.nameHint() : "method";
                yield new InvocationExpr(null, mName, args, insn.resultType());
            }

            // Type cast
            case CAST -> {
                Expression operand = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("?");
                yield new CastExpr(insn.resultType(), operand);
            }

            // Object creation
            case NEW -> new NewExpr(insn.resultType(), List.of(), List.of());
            case NEW_ARRAY -> new NewExpr(insn.resultType(), List.of(new VarExpr("?")), List.of());

            // instanceof
            case INSTANCE_OF -> new VarExpr("/* instanceof */");

            // Array length
            case ARRAY_LENGTH -> {
                Expression arr = !insn.operands().isEmpty()
                        ? valueToExpr(insn.operands().getFirst()) : new VarExpr("arr");
                yield new FieldAccessExpr(arr, "length");
            }

            // Increment
            case INC -> {
                if (insn.operands().size() >= 2 && insn.operands().getFirst() instanceof Variable v) {
                    Value incr = insn.operands().get(1);
                    Expression rhs = valueToExpr(incr);
                    yield new AssignExpr(new VarExpr("var" + v.slot()),
                            new BinExpr(BinaryOperator.ADD, new VarExpr("var" + v.slot()), rhs));
                }
                yield new VarExpr("/* inc */");
            }

            // Throw
            case THROW -> {
                yield !insn.operands().isEmpty() ? valueToExpr(insn.operands().getFirst()) : new VarExpr("ex");
            }

            default -> new VarExpr("/* " + insn.opcode() + " */");
        };
    }

    /** Convert a Value (Variable / ConstantValue / InstructionRef) to an Expression. */
    private Expression valueToExpr(Value v) {
        return switch (v) {
            case Variable var -> new VarExpr("var" + var.slot());
            case ConstantValue cv -> {
                Object val = cv.value();
                if (val == null) {
                    yield new VarExpr("null");
                }
                yield new LitExpr(val, cv.type());
            }
            case InstructionRef ref -> new VarExpr("tmp" + ref.instruction().id());
            default -> new VarExpr("?");
        };
    }

    /** Convert CONST IR to a LitExpr. */
    private Expression constToExpr(IrInstruction insn) {
        if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof ConstantValue cv) {
            Object v = cv.value();
            if (v instanceof String s) {
                return new LitExpr(s, JavaType.classType("java/lang/String"));
            }
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
        // Find the switch discriminant from CONDITION or a LOAD of the switch key
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

    /** Build a TryStatement from try-catch info. */
    private TryStatement buildTryCatch(TryCatchInfo info, Statement tryBody) {
        List<TryStatement.CatchClause> catchClauses = new ArrayList<>();
        // Simplify catch type to simple name
        String excType = info.catchType();
        if (excType != null && excType.contains("/")) {
            excType = excType.substring(excType.lastIndexOf('/') + 1);
        }
        // Handler body is processed inline by BlockReducer; wrap try body
        catchClauses.add(new TryStatement.CatchClause(
                excType != null ? excType : "Exception",
                "e",
                new BlockStatement(List.of(new ExpressionStatement(new VarExpr("/* handler */"))))));
        return new TryStatement(tryBody, catchClauses, null);
    }

    /** Translate a single block group to a list of statements (helper for switch). */
    private List<Statement> translateBlockGroup(BlockGroup group, LinearIr ir) {
        List<Statement> result = new ArrayList<>();
        for (IrInstruction insn : group.allIrInstructions(ir)) {
            if (insn.opcode() == IrOpcode.CONDITION) {
                continue;
            }
            Statement s = translateStmt(insn);
            if (s != null) {
                result.add(s);
            }
        }
        return result;
    }

    // ── BlockGroup helper ─────────────────────────────────────────────

    private static class BlockGroup {

        private final List<BasicBlock> blocks = new ArrayList<>();

        BlockGroup(BasicBlock first) {blocks.add(first);}

        void add(BasicBlock b) {blocks.add(b);}

        BasicBlock first() {return blocks.getFirst();}

        BasicBlock last() {return blocks.getLast();}

        List<IrInstruction> allIrInstructions(LinearIr ir) {
            List<IrInstruction> result = new ArrayList<>();
            for (BasicBlock b : blocks) {
                result.addAll(ir.instructionsOf(b));
            }
            return result;
        }
    }
}
