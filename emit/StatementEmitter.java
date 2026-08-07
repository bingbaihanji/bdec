package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;

public class StatementEmitter {

    private final IndentWriter w;

    private final ExpressionEmitter exprs;

    public StatementEmitter(IndentWriter w, ExpressionEmitter exprs) {
        this.w = w;
        this.exprs = exprs;
    }

    public void emit(Statement stmt) {
        switch (stmt.kind()) {
            case BLOCK -> {
                BlockStatement b = (BlockStatement) stmt;
                w.write("{").newLine();
                w.indent();
                for (Statement s : b.statements()) {
                    emit(s);
                }
                w.dedent();
                w.write("}").newLine();
            }
            case IF -> {
                IfStatement i = (IfStatement) stmt;
                w.token("if").space().write("(");
                exprs.emit(i.condition());
                w.write(")").space();
                emitBranched(i.thenBranch());
                if (i.elseBranch() != null) {
                    w.space().token("else").space();
                    emitBranched(i.elseBranch());
                }
            }
            case LOOP -> {
                LoopStatement l = (LoopStatement) stmt;
                String kw = switch (l.loopKind()) {
                    case WHILE -> "while";
                    case DO_WHILE -> "do";
                    case FOR, FOR_EACH -> "for";
                };
                if (l.loopKind() == LoopStatement.LoopKind.DO_WHILE) {
                    w.token("do").space();
                    emitBranched(l.body());
                    w.space().token("while").space().write("(");
                    if (l.condition() != null) {
                        exprs.emit(l.condition());
                    }
                    w.write(");").newLine();
                } else {
                    w.token(kw).space().write("(");
                    if (l.condition() != null) {
                        exprs.emit(l.condition());
                    }
                    w.write(")").space();
                    emitBranched(l.body());
                }
            }
            case RETURN -> {
                ReturnStatement r = (ReturnStatement) stmt;
                w.token("return");
                if (r.value() != null) {
                    w.space();
                    exprs.emit(r.value());
                }
                w.write(";").newLine();
            }
            case EXPRESSION_STMT -> {
                ExpressionStatement e = (ExpressionStatement) stmt;
                exprs.emit(e.expression());
                w.write(";").newLine();
            }
            default -> {
                w.write("// " + stmt.kind()).newLine();
            }
        }
    }

    private void emitBranched(Statement stmt) {
        if (stmt instanceof BlockStatement) {
            emit(stmt);
        } else {
            w.write("{").newLine();
            w.indent();
            emit(stmt);
            w.dedent();
            w.write("}").newLine();
        }
    }
}
