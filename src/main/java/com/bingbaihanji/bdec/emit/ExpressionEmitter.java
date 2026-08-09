package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InstanceOfExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Emits AST expressions to Java source text. Implements AstVisitor for dispatch. */
public class ExpressionEmitter implements AstVisitor<Void, Void> {

    private final IndentWriter w;

    private final Set<String> importedPackages;

    public ExpressionEmitter(IndentWriter w) {
        this(w, List.of());
    }

    public ExpressionEmitter(IndentWriter w, List<String> imports) {
        this.w = w;
        this.importedPackages = new HashSet<>();
        for (String imp : imports) {
            // Convert import like "java.util.List" to package "java.util"
            int lastDot = imp.lastIndexOf('.');
            if (lastDot >= 0) {
                importedPackages.add(imp.substring(0, lastDot));
            }
        }
    }

    static String opSymbol(BinaryOperator op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case REM -> "%";
            case EQ -> "==";
            case NE -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case AND -> "&&";
            case OR -> "||";
            case BIT_AND -> "&";
            case BIT_OR -> "|";
            case BIT_XOR -> "^";
            case SHL -> "<<";
            case SHR -> ">>";
            case USHR -> ">>>";
            case INSTANCEOF -> "instanceof";
        };
    }

    // ── AstVisitor ─────────────────────────────────────────────────

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeChar(char c) {
        return switch (c) {
            case '\'' -> "\\'";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> String.valueOf(c);
        };
    }

    // ── Emit dispatch ──────────────────────────────────────────────

    /** Map BinaryOperator to its compound-assignment symbol prefix. */
    private static String compoundSym(BinaryOperator op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case REM -> "%";
            case BIT_AND -> "&";
            case BIT_OR -> "|";
            case BIT_XOR -> "^";
            case SHL -> "<<";
            case SHR -> ">>";
            case USHR -> ">>>";
            default -> "?";
        };
    }

    // ── Individual emitters ────────────────────────────────────────

    /** Resolve a type name to its shortest valid form:
     *  - java.lang.* types → simple name
     *  - types matching an import → simple name
     *  - otherwise → full qualified name. */
    String typeName(JavaType type) {
        if (type.kind() == TypeKind.CLASS && type.internalName() != null) {
            String internal = type.internalName();
            String full = internal.replace('/', '.');
            // java.lang always gets short name
            if (full.startsWith("java.lang.") && full.indexOf('.', 10) < 0) {
                return full.substring(10);
            }
            // Types with matching imports get short name
            int lastSlash = internal.lastIndexOf('/');
            if (lastSlash >= 0) {
                String pkg = internal.substring(0, lastSlash).replace('/', '.');
                if (importedPackages.contains(pkg)) {
                    return internal.substring(lastSlash + 1);
                }
            }
            return full;
        }
        // For arrays, delegate to type's own displayName (handled recursively)
        return type.displayName();
    }

    @Override
    public Void visitStatement(Statement stmt, Void context) {
        return null; // statements handled by StatementEmitter
    }

    @Override
    public Void visitExpression(Expression expr, Void context) {
        emit(expr);
        return null;
    }

    public void emit(Expression expr) {
        switch (expr.kind()) {
            case VARIABLE -> emitVar((VarExpr) expr);
            case LITERAL -> emitLiteral((LitExpr) expr);
            case BINARY -> emitBinary((BinExpr) expr);
            case UNARY -> emitUnary((UnExpr) expr);
            case ASSIGNMENT -> emitAssign((AssignExpr) expr);
            case CONDITIONAL -> emitConditional((CondExpr) expr);
            case INVOCATION -> emitInvocation((InvocationExpr) expr);
            case FIELD_ACCESS -> emitFieldAccess((FieldAccessExpr) expr);
            case CAST -> emitCast((CastExpr) expr);
            case NEW -> emitNew((NewExpr) expr);
            case INSTANCE_OF -> emitInstanceOf((InstanceOfExpr) expr);
            case ARRAY_ACCESS -> emitArrayAccess((ArrayAccessExpr) expr);
            default -> w.write("/*" + expr.kind() + "*/");
        }
    }

    private void emitVar(VarExpr v) {
        w.write(v.name());
    }

    private void emitLiteral(LitExpr lit) {
        Object v = lit.value();
        if (v == null) {
            w.write("null");
        } else if (v instanceof String s) {
            w.write("\"").write(escapeString(s)).write("\"");
        } else if (v instanceof Character c) {
            w.write("'").write(escapeChar(c)).write("'");
        } else if (v instanceof Boolean b) {
            w.write(b ? "true" : "false");
        } else if (v instanceof Long l) {
            w.write(String.valueOf(l)).write("L");
        } else if (v instanceof Float f) {
            if (Float.isNaN(f)) {
                w.write("Float.NaN");
            } else if (Float.isInfinite(f)) {
                w.write(f > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY");
            } else {
                w.write(String.valueOf(f)).write("f");
            }
        } else if (v instanceof Double d) {
            if (Double.isNaN(d)) {
                w.write("Double.NaN");
            } else if (Double.isInfinite(d)) {
                w.write(d > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY");
            } else {
                w.write(String.valueOf(d));
            }
        } else {
            w.write(String.valueOf(v));
        }
    }

    private void emitBinary(BinExpr bin) {
        BinaryOperator op = bin.operator();
        Expression left = bin.left(), right = bin.right();
        emitWithParens(left, bin.precedence());
        w.write(" ").write(opSymbol(op)).write(" ");
        emitWithParens(right, bin.precedence());
    }

    private void emitUnary(UnExpr un) {
        UnaryOperator op = un.operator();
        String sym = switch (op) {
            case NEG -> "-";
            case NOT -> "!";
            case COMPLEMENT -> "~";
            case PRE_INC -> "++";
            case PRE_DEC -> "--";
            case POST_INC, POST_DEC -> "";
        };
        w.write(sym);
        emit(un.operand());
        if (op == UnaryOperator.POST_INC) {
            w.write("++");
        } else if (op == UnaryOperator.POST_DEC) {
            w.write("--");
        }
    }

    private void emitAssign(AssignExpr a) {
        emit(a.target());
        if (a.compoundOp() != null) {
            w.space().write(compoundSym(a.compoundOp())).write("= ");
        } else {
            w.write(" = ");
        }
        emit(a.value());
    }

    private void emitConditional(CondExpr c) {
        emitWithParens(c.condition(), c.precedence());
        w.write(" ? ");
        emit(c.trueExpr());
        w.write(" : ");
        emit(c.falseExpr());
    }

    private void emitInvocation(InvocationExpr inv) {
        if (inv.target() != null) {
            emit(inv.target());
            w.write(".");
        }
        w.write(inv.methodName()).write("(");
        List<Expression> args = inv.arguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                w.write(", ");
            }
            emit(args.get(i));
        }
        w.write(")");
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void emitFieldAccess(FieldAccessExpr fa) {
        if (fa.target() != null) {
            emit(fa.target());
            w.write(".");
        }
        w.write(fa.fieldName());
    }

    private void emitInstanceOf(InstanceOfExpr io) {
        if (io.operand() != null) {
            emitWithParens(io.operand(), io.precedence());
        } else {
            w.write("obj");
        }
        w.write(" instanceof ");
        w.write(typeName(io.targetType()));
    }

    private void emitArrayAccess(ArrayAccessExpr aa) {
        if (aa.array() != null) {
            emitWithParens(aa.array(), aa.precedence());
        } else {
            w.write("arr");
        }
        w.write("[");
        if (aa.index() != null) {
            emit(aa.index());
        }
        w.write("]");
    }

    private void emitCast(CastExpr cast) {
        w.write("(").write(typeName(cast.targetType())).write(") ");
        emitWithParens(cast.operand(), cast.precedence());
    }

    private void emitNew(NewExpr n) {
        w.write("new ").write(typeName(n.instantiatedType()));
        if (!n.constructorArgs().isEmpty()) {
            w.write("(");
            List<Expression> args = n.constructorArgs();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    w.write(", ");
                }
                emit(args.get(i));
            }
            w.write(")");
        } else if (!n.dimensions().isEmpty()) {
            w.write("[");
            for (int i = 0; i < n.dimensions().size(); i++) {
                if (i > 0) {
                    w.write("][");
                }
                emit(n.dimensions().get(i));
            }
            w.write("]");
        } else {
            w.write("()");
        }
    }

    private void emitWithParens(Expression expr, int parentPrecedence) {
        if (expr.precedence() < parentPrecedence) {
            w.write("(");
            emit(expr);
            w.write(")");
        } else {
            emit(expr);
        }
    }
}
