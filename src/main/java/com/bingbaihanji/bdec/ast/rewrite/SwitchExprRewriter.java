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
 * <p>Pattern detection: when every case branch (including default)
 * ends with a {@code return} of the same expression shape, or every
 * branch assigns to the same variable, we mark the switch as an expression.
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
                        md.typeParameters(),
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
            List<Statement> rewritten = new ArrayList<>();
            for (Statement cs : bs.statements()) {
                Statement rs = rewriteStatement(cs);
                if (rs instanceof SwitchStatement sw && isSwitchExpression(sw)) {
                    rewritten.add(new SwitchStatement(sw.discriminant(), sw.cases(), true));
                } else {
                    rewritten.add(rs);
                }
            }
            return new BlockStatement(rewritten);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        if (s instanceof LoopStatement l) {
            if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH) {
                return new LoopStatement(l.loopKind(), l.forEachVar(), l.condition(),
                        rewriteStatement(l.body()));
            }
            return new LoopStatement(l.loopKind(), l.condition(), rewriteStatement(l.body()));
        }
        return s;
    }

    /**
     * Detect if a switch statement is used as an expression.
     * Pattern: every case group (including default) ends with either
     * a {@code return} statement or an assignment to the same variable
     * followed by {@code break}.
     */
    private boolean isSwitchExpression(SwitchStatement sw) {
        if (sw.cases().isEmpty()) return false;
        String commonTarget = null;

        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            List<Statement> body = cg.body();
            if (body.isEmpty()) return false;
            Statement last = body.getLast();

            if (last instanceof ReturnStatement) {
                // Each case returns directly → switch expression returning the value
                continue;
            }

            // Check for assignment + break pattern
            if (last instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr assign
                    && assign.target() instanceof VarExpr ve) {
                if (commonTarget == null) {
                    commonTarget = ve.name();
                } else if (!commonTarget.equals(ve.name())) {
                    return false; // different assignment targets
                }
            } else {
                return false; // case doesn't end with return or assignment
            }
        }
        return true;
    }
}
