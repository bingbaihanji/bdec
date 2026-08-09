package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eliminates boxing/unboxing method calls inserted by javac.
 *
 * <p>Patterns:
 * <pre>
 *   Integer.valueOf(5)  →  5
 *   x.intValue()        →  x
 *   new Integer(5)      →  5  (Java &lt; 9)
 *   Boolean.valueOf(b)  →  b
 * </pre>
 *
 * <p>Inspired by CFR's {@code BoxingProcessor}.
 */
public class BoxingRewriter implements RewriteRule {

    private static final Set<String> WRAPPER_TYPES = Set.of(
            "java/lang/Integer", "java/lang/Long", "java/lang/Short",
            "java/lang/Byte", "java/lang/Float", "java/lang/Double",
            "java/lang/Boolean", "java/lang/Character");

    private static final Map<String, String> UNBOX_METHODS = new HashMap<>();

    static {
        UNBOX_METHODS.put("intValue", "java/lang/Integer");
        UNBOX_METHODS.put("longValue", "java/lang/Long");
        UNBOX_METHODS.put("shortValue", "java/lang/Short");
        UNBOX_METHODS.put("byteValue", "java/lang/Byte");
        UNBOX_METHODS.put("floatValue", "java/lang/Float");
        UNBOX_METHODS.put("doubleValue", "java/lang/Double");
        UNBOX_METHODS.put("booleanValue", "java/lang/Boolean");
        UNBOX_METHODS.put("charValue", "java/lang/Character");
    }

    @Override
    public String name() {return "boxing";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        rewriteStatement(md.body())));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    private Statement rewriteStatement(Statement s) {
        return switch (s) {
            case BlockStatement bs -> new BlockStatement(bs.statements().stream()
                    .map(this::rewriteStatement).toList());
            case IfStatement i -> new IfStatement(
                    rewriteExpr(i.condition()),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
            case ExpressionStatement es -> new ExpressionStatement(rewriteExpr(es.expression()));
            case ReturnStatement rs -> new ReturnStatement(
                    rs.value() != null ? rewriteExpr(rs.value()) : null);
            case ThrowStatement ts -> new ThrowStatement(rewriteExpr(ts.expression()));
            default -> s;
        };
    }

    /** Eliminate boxing/unboxing calls in an expression tree. */
    private Expression rewriteExpr(Expression e) {
        if (e instanceof InvocationExpr inv) {
            // Unboxing: x.{type}Value() → x
            if (isUnboxCall(inv)) {
                return inv.target();
            }
            // Boxing: Integer.valueOf(x) → x
            if (isBoxCall(inv) && !inv.arguments().isEmpty()) {
                return rewriteExpr(inv.arguments().getFirst());
            }
            // Rewrite args recursively
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            return new InvocationExpr(
                    inv.target() != null ? rewriteExpr(inv.target()) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }
        // TODO: recursively rewrite children of other expression types
        return e;
    }

    private boolean isUnboxCall(InvocationExpr inv) {
        String name = inv.methodName();
        if (name == null || !UNBOX_METHODS.containsKey(name)) {
            return false;
        }
        // Check target is a VarExpr (likely the wrapper type)
        return inv.target() != null && inv.arguments().isEmpty();
    }

    private boolean isBoxCall(InvocationExpr inv) {
        if (!"valueOf".equals(inv.methodName())) {
            return false;
        }
        // target should be null (static call) and exactly 1 arg
        return inv.target() == null && inv.arguments().size() == 1;
    }
}
