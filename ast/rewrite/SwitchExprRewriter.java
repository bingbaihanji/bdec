package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects switch-expression patterns and converts them to
 * Java 14+ switch expressions with arrow cases and {@code yield}.
 *
 * <p>Pattern (assignment to same variable from multiple case blocks):
 * <pre>
 *   int result;
 *   switch (x) {
 *       case 1: result = 10; break;
 *       case 2: result = 20; break;
 *       default: result = 0;
 *   }
 *
 *   → int result = switch (x) {
 *       case 1 -> 10;
 *       case 2 -> 20;
 *       default -> 0;
 *   };
 * </pre>
 *
 * <p>Inspired by Vineflower's {@code SwitchExpressionHelper}.
 */
public class SwitchExprRewriter implements RewriteRule {

    @Override
    public String name() { return "switch-expr"; }

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
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(this::rewriteStatement).toList());
        }
        return s;
    }

    /**
     * Detect switch-to-assignment patterns.
     * This is a placeholder for the full implementation which requires
     * control flow analysis to detect phi-like assignments.
     * The full version would:
     * 1. Find SwitchStatement nodes
     * 2. Check if each case branch ends with an assignment to the same target
     * 3. Convert to switch expression with arrow cases
     */
    @SuppressWarnings("unused")
    private boolean isSwitchExpression(SwitchStatement sw, BlockStatement parent) {
        // Placeholder: full implementation requires data-flow analysis
        return false;
    }
}
