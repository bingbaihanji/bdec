package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;

/** Emits AST statements to Java source text. Implements AstVisitor for dispatch. */
public class StatementEmitter implements AstVisitor<Void, Void> {

    private final IndentWriter w;

    private final ExpressionEmitter exprs;

    private final String className;

    public StatementEmitter(IndentWriter w, ExpressionEmitter exprs, String className) {
        this.w = w;
        this.exprs = exprs;
        this.className = className;
    }

    // ── AstVisitor ─────────────────────────────────────────────────

    @Override
    public Void visitStatement(Statement stmt, Void context) {
        emit(stmt);
        return null;
    }

    @Override
    public Void visitExpression(Expression expr, Void context) {
        exprs.emit(expr);
        return null;
    }

    // ── Emit dispatch ──────────────────────────────────────────────

    public void emit(Statement stmt) {
        switch (stmt.kind()) {
            case BLOCK -> emitBlock((BlockStatement) stmt);
            case IF -> emitIf((IfStatement) stmt);
            case LOOP -> emitLoop((LoopStatement) stmt);
            case RETURN -> emitReturn((ReturnStatement) stmt);
            case METHOD_DECL -> emitMethodDecl((MethodDeclaration) stmt);
            case EXPRESSION_STMT -> emitExprStmt((ExpressionStatement) stmt);
            case FIELD_DECL -> emitFieldDecl((FieldDeclaration) stmt);
            case THROW -> emitThrow(stmt);
            case SWITCH -> emitSwitch(stmt);
            case VARIABLE_DECL -> w.write("/* var decl */;").newLine();
            case BREAK -> w.token("break").write(";").newLine();
            case CONTINUE -> w.token("continue").write(";").newLine();
            case SYNCHRONIZED -> w.write("/* synchronized */").newLine();
            case TRY -> emitTry(stmt);
            default -> w.write("// " + stmt.kind()).newLine();
        }
    }

    // ── Individual emitters ────────────────────────────────────────

    private void emitBlock(BlockStatement b) {
        w.write("{").newLine();
        w.indent();
        for (Statement s : b.statements()) {
            emit(s);
        }
        w.dedent();
        w.write("}").newLine();
    }

    private void emitIf(IfStatement i) {
        w.token("if").space().write("(");
        exprs.emit(i.condition());
        w.write(")").space();
        emitBranched(i.thenBranch());
        if (i.elseBranch() != null) {
            w.space().token("else").space();
            emitBranched(i.elseBranch());
        }
    }

    private void emitLoop(LoopStatement l) {
        switch (l.loopKind()) {
            case DO_WHILE -> {
                w.token("do").space();
                emitBranched(l.body());
                w.space().token("while").space().write("(");
                if (l.condition() != null) {
                    exprs.emit(l.condition());
                }
                w.write(");").newLine();
            }
            case FOR, FOR_EACH -> {
                w.token("for").space().write("(");
                // Emit initializer
                if (l.initExpr() != null) {
                    exprs.emit(l.initExpr());
                }
                w.write("; ");
                // Emit condition
                if (l.condition() != null) {
                    exprs.emit(l.condition());
                }
                w.write("; ");
                // Emit increment
                if (l.incrExpr() != null) {
                    exprs.emit(l.incrExpr());
                }
                w.write(")").space();
                emitBranched(l.body());
            }
            case WHILE -> {
                w.token("while").space().write("(");
                if (l.condition() != null) {
                    exprs.emit(l.condition());
                }
                w.write(")").space();
                emitBranched(l.body());
            }
        }
    }

    private void emitReturn(ReturnStatement r) {
        w.token("return");
        if (r.value() != null) {
            w.space();
            exprs.emit(r.value());
        }
        w.write(";").newLine();
    }

    private void emitMethodDecl(MethodDeclaration m) {
        // Method modifiers
        emitMethodModifiers(m.accessFlags());

        // Return type and name
        String methodName = m.name();
        if (methodName == null || methodName.isEmpty()) {
            // Static initializer — just emit "static { }"
            w.write("{").newLine();
            w.indent();
            if (m.body() != null) {
                if (m.body() instanceof BlockStatement bs) {
                    for (Statement s : bs.statements()) {
                        emit(s);
                    }
                } else {
                    emit(m.body());
                }
            }
            w.dedent();
            w.write("}").newLine();
            return;
        }

        w.write(m.returnType().displayName()).space().write(methodName).write("(");

        // Parameters
        for (int i = 0; i < m.parameterNames().length; i++) {
            if (i > 0) {
                w.write(", ");
            }
            w.write(m.parameterTypes()[i].displayName()).space().write(m.parameterNames()[i]);
        }
        w.write(")").space();

        // Body
        if (m.body() != null) {
            emit(m.body());
        } else {
            w.write(";").newLine();
        }
    }

    private void emitExprStmt(ExpressionStatement e) {
        exprs.emit(e.expression());
        w.write(";").newLine();
    }

    private void emitFieldDecl(FieldDeclaration f) {
        emitFieldModifiers(f.accessFlags());
        w.write(f.type().displayName()).space().write(f.name());
        if (f.initializer() != null) {
            w.space().write("=").space();
            exprs.emit(f.initializer());
        }
        w.write(";").newLine();
    }

    private void emitTry(Statement stmt) {
        if (stmt instanceof TryStatement tryStmt) {
            w.token("try").space();
            emitBranched(tryStmt.tryBody());
            for (TryStatement.CatchClause cc : tryStmt.catchClauses()) {
                w.space().token("catch").space().write("(")
                        .write(cc.exceptionType()).space().write(cc.varName()).write(")").space();
                emitBranched(cc.body());
            }
            if (tryStmt.finallyBody() != null) {
                w.space().token("finally").space();
                emitBranched(tryStmt.finallyBody());
            }
        } else {
            w.write("/* try */").newLine();
        }
    }

    private void emitThrow(Statement stmt) {
        w.token("throw").space();
        if (!stmt.children().isEmpty() && stmt.children().getFirst() instanceof Expression ex) {
            exprs.emit(ex);
        } else {
            w.write("new RuntimeException()");
        }
        w.write(";").newLine();
    }

    private void emitSwitch(Statement stmt) {
        if (stmt instanceof SwitchStatement sw) {
            w.token("switch").space().write("(");
            exprs.emit(sw.discriminant());
            w.write(")").space().write("{").newLine();
            w.indent();
            for (SwitchStatement.CaseGroup cg : sw.cases()) {
                if (cg.isDefault()) {
                    w.token("default").write(":").newLine();
                } else {
                    for (Expression label : cg.labels()) {
                        w.token("case").space();
                        exprs.emit(label);
                        w.write(":").newLine();
                    }
                }
                w.indent();
                for (Statement s : cg.body()) {
                    emit(s);
                }
                w.dedent();
            }
            w.dedent();
            w.write("}").newLine();
        } else {
            // Fallback: generic switch placeholder
            w.token("switch").space().write("(");
            if (!stmt.children().isEmpty() && stmt.children().getFirst() instanceof Expression ex) {
                exprs.emit(ex);
            } else {
                w.write("/* expr */");
            }
            w.write(")").space().write("{").newLine();
            w.indent();
            w.write("// TODO: full switch emission").newLine();
            w.dedent();
            w.write("}").newLine();
        }
    }

    // ── Modifier helpers ──────────────────────────────────────────

    private void emitMethodModifiers(int flags) {
        if ((flags & 0x0001) != 0) {
            w.token("public").space();
        } else if ((flags & 0x0002) != 0) {
            w.token("private").space();
        } else if ((flags & 0x0004) != 0) {
            w.token("protected").space();
        }
        if ((flags & 0x0008) != 0) {
            w.token("static").space();
        }
        if ((flags & 0x0010) != 0) {
            w.token("final").space();
        }
        if ((flags & 0x0020) != 0) {
            w.token("synchronized").space();
        }
        if ((flags & 0x0100) != 0) {
            w.token("native").space();
        }
        if ((flags & 0x0400) != 0) {
            w.token("abstract").space();
        }
    }

    private void emitFieldModifiers(int flags) {
        if ((flags & 0x0001) != 0) {
            w.token("public").space();
        } else if ((flags & 0x0002) != 0) {
            w.token("private").space();
        } else if ((flags & 0x0004) != 0) {
            w.token("protected").space();
        }
        if ((flags & 0x0008) != 0) {
            w.token("static").space();
        }
        if ((flags & 0x0010) != 0) {
            w.token("final").space();
        }
        if ((flags & 0x0040) != 0) {
            w.token("volatile").space();
        }
        if ((flags & 0x0080) != 0) {
            w.token("transient").space();
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
