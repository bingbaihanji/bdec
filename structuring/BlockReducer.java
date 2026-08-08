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
                                 Map<BasicBlock, IfInfo> ifAnns) {
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

            // Wrap in structured AST if this block is a loop/if header
            BasicBlock header = group.first();
            if (loopAnns.containsKey(header)) {
                Expression cond = extractCondition(group, ir);
                s = new LoopStatement(LoopStatement.LoopKind.WHILE, cond, s);
            } else if (ifAnns.containsKey(header)) {
                Expression cond = extractCondition(group, ir);
                s = new IfStatement(cond != null ? cond : new VarExpr("/*condition*/"),
                        s, null);
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
                yield new FieldAccessExpr(obj, "field");
            }

            // Field store → assignment to field
            case FIELD_STORE -> {
                Value obj = !insn.operands().isEmpty() ? insn.operands().getFirst() : null;
                Value val = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                Expression lhs = obj instanceof Variable
                        ? new FieldAccessExpr(new VarExpr("var" + ((Variable) obj).slot()), "field")
                        : new FieldAccessExpr(null, "field");
                Expression rhs = val != null ? valueToExpr(val) : new VarExpr("?");
                yield new AssignExpr(lhs, rhs);
            }

            // Binary arithmetic
            case BINARY -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    yield new BinExpr(BinaryOperator.ADD, left, right); // TODO: infer operator
                }
                yield new VarExpr("/* binary */");
            }

            // Comparisons
            case COMPARE -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    yield new BinExpr(BinaryOperator.EQ, left, right); // TODO: infer comparison
                }
                yield new VarExpr("/* compare */");
            }

            // Condition (if used in expression context)
            case CONDITION -> {
                if (insn.operands().size() >= 2) {
                    Expression left = valueToExpr(insn.operands().get(0));
                    Expression right = valueToExpr(insn.operands().get(1));
                    yield new BinExpr(BinaryOperator.EQ, left, right);
                }
                yield new VarExpr("/* condition */");
            }

            // Unary negation
            case UNARY -> {
                if (!insn.operands().isEmpty()) {
                    yield new UnExpr(UnaryOperator.NEG, valueToExpr(insn.operands().getFirst()));
                }
                yield new VarExpr("/* unary */");
            }

            // Method invocation
            case INVOKE -> {
                List<Expression> args = new ArrayList<>();
                int start = 0;
                // First operand may be target object, rest are args
                for (int i = start; i < insn.operands().size(); i++) {
                    args.add(valueToExpr(insn.operands().get(i)));
                }
                yield new InvocationExpr(null, "method", args, insn.resultType());
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
