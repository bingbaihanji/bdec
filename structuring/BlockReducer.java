package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;

import java.util.ArrayList;
import java.util.List;

public final class BlockReducer {

    public BlockStatement reduce(ControlFlowGraph graph) {
        List<Statement> statements = new ArrayList<>();

        // Walk blocks in order, collecting structured output
        for (BasicBlock block : graph.blocks()) {
            if (block == graph.entryBlock() || block == graph.exitBlock()) {
                continue;
            }
            if (block.instructions().isEmpty()) {
                continue;
            }

            Statement stmt = translateBlock(block, graph);
            if (stmt != null) {
                statements.add(stmt);
            }
        }

        return new BlockStatement(statements);
    }

    private Statement translateBlock(BasicBlock block, ControlFlowGraph graph) {
        List<Instruction> insns = block.instructions();
        if (insns.isEmpty()) {
            return null;
        }

        Instruction last = block.lastInstruction();
        if (last == null) {
            return null;
        }

        String m = last.mnemonic();

        // Return statement
        if (m.contains("return")) {
            return new ReturnStatement(
                    m.equals("return") ? null : new VarExpr("result"));
        }

        // Conditional branch → placeholder expression
        if (m.startsWith("if")) {
            return new ExpressionStatement(
                    new VarExpr("// " + m + " (condition)"));
        }

        // Goto → placeholder
        if (m.equals("goto")) {
            return new ExpressionStatement(
                    new VarExpr("// goto"));
        }

        // Default: expression statement with block marker
        return new ExpressionStatement(
                new VarExpr("// B" + block.id() + ": " + m));
    }
}
