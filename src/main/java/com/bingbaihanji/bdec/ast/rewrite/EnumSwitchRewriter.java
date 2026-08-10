package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects javac-generated enum switch scaffolding and collapses
 * it back to a native {@code switch (enumValue)} statement.
 *
 * <p>When javac compiles {@code switch(enumValue)}, it generates a
 * synthetic inner class containing a {@code $SwitchMap$} int array
 * that maps enum ordinals to small sequential integers (1, 2, 3...).
 * The actual switch then uses this array:
 * <pre>
 *   switch ($SwitchMap$EnumClass[enumValue.ordinal()]) {
 *       case 1: ... break;
 *       case 2: ... break;
 *   }
 * </pre>
 *
 * <p>This rewriter detects the {@code $SwitchMap$} pattern in switch
 * discriminants, replaces the array-and-ordinal expression with the
 * original enum variable, and removes the synthetic SwitchMap class
 * if it appears in the compilation unit.
 *
 * <p>Inspired by CFR's {@code SwitchReWriter.rewriteEnumSwitch()}
 * and Vineflower's {@code SwitchMapProcessor}.
 */
public class EnumSwitchRewriter implements RewriteRule {

    /** Check if a field name indicates a SwitchMap int array. */
    private static boolean isSwitchMapFieldName(String name) {
        return name.startsWith("$SwitchMap$") || name.contains("SwitchMap");
    }

    /** Extract the field name from an expression that might be a {@link FieldAccessExpr}. */
    private static String extractFieldName(Expression expr) {
        if (expr instanceof FieldAccessExpr fae) {
            return fae.fieldName();
        }
        return null;
    }

    // ── Phase 1: collect SwitchMap info ────────────────────────────────

    @Override
    public String name() {return "enum-switch";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        // Phase 1: Collect $SwitchMap$ field names from all types at all nesting levels.
        Set<String> switchMapFieldNames = new HashSet<>();
        collectSwitchMapFieldNames(unit.types(), switchMapFieldNames);

        // Phase 2: Rewrite each type — find and rewrite enum switch discriminants,
        // and remove synthetic SwitchMap types.
        List<TypeDeclaration> newTypes = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            TypeDeclaration rewritten = rewriteType(td, switchMapFieldNames);
            if (rewritten != null) {
                newTypes.add(rewritten);
            }
        }

        return new CompilationUnit(unit.packageName(), unit.imports(), newTypes);
    }

    /** Recursively collect field names that look like SwitchMap arrays. */
    private void collectSwitchMapFieldNames(List<TypeDeclaration> types, Set<String> names) {
        for (TypeDeclaration td : types) {
            collectFromType(td, names);
        }
    }

    // ── Phase 2: rewrite types ─────────────────────────────────────────

    private void collectFromType(TypeDeclaration td, Set<String> names) {
        for (AstNode member : td.children()) {
            if (member instanceof FieldDeclaration fd) {
                String name = fd.name();
                if (name != null && isSwitchMapFieldName(name)) {
                    names.add(name);
                }
            } else if (member instanceof TypeDeclaration nested) {
                collectFromType(nested, names);
            }
        }
    }

    /**
     * Rewrite a TypeDeclaration:
     * <ul>
     *   <li>If the type itself is a SwitchMap holder, return {@code null} to remove it.</li>
     *   <li>Otherwise recursively rewrite methods (converting enum switches) and nested types.</li>
     * </ul>
     */
    private TypeDeclaration rewriteType(TypeDeclaration td, Set<String> switchMapFieldNames) {
        if (isSwitchMapType(td, switchMapFieldNames)) {
            return null;
        }

        List<AstNode> newMembers = new ArrayList<>();
        for (AstNode member : td.children()) {
            if (member instanceof TypeDeclaration nested) {
                TypeDeclaration rewritten = rewriteType(nested, switchMapFieldNames);
                if (rewritten != null) {
                    newMembers.add(rewritten);
                }
            } else if (member instanceof MethodDeclaration md) {
                newMembers.add(rewriteMethod(md, switchMapFieldNames));
            } else {
                newMembers.add(member);
            }
        }

        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), newMembers);
    }

    // ── Method and statement rewriting ─────────────────────────────────

    /**
     * Determine if a type is a synthetic SwitchMap holder that should be removed.
     * Checks the type name for SwitchMap-like patterns, and also checks whether
     * the type contains {@code $SwitchMap$} static fields (for anonymous holders).
     */
    private boolean isSwitchMapType(TypeDeclaration td, Set<String> switchMapFieldNames) {
        String name = td.simpleName();
        if (name != null && (name.startsWith("$SwitchMap$") || name.contains("SwitchMap"))) {
            return true;
        }
        // Anonymous inner class holder: check if this type's fields include
        // a $SwitchMap$ field that we already identified.
        for (AstNode member : td.children()) {
            if (member instanceof FieldDeclaration fd) {
                String fieldName = fd.name();
                if (fieldName != null && switchMapFieldNames.contains(fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private MethodDeclaration rewriteMethod(MethodDeclaration md, Set<String> switchMapFieldNames) {
        Statement newBody = md.body() != null ? rewriteStatement(md.body(), switchMapFieldNames) : null;
        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                md.parameterNames(), md.parameterTypes(), md.typeParameters(), newBody);
    }

    private Statement rewriteStatement(Statement s, Set<String> switchMapFieldNames) {
        if (s instanceof BlockStatement bs) {
            List<Statement> newStmts = new ArrayList<>();
            for (Statement st : bs.statements()) {
                newStmts.add(rewriteStatement(st, switchMapFieldNames));
            }
            return new BlockStatement(newStmts);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch(), switchMapFieldNames),
                    i.elseBranch() != null
                            ? rewriteStatement(i.elseBranch(), switchMapFieldNames) : null);
        }
        if (s instanceof LoopStatement l) {
            if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH) {
                return new LoopStatement(l.loopKind(), l.forEachVar(), l.condition(),
                        rewriteStatement(l.body(), switchMapFieldNames));
            }
            return new LoopStatement(l.loopKind(), l.condition(),
                    rewriteStatement(l.body(), switchMapFieldNames));
        }
        if (s instanceof TryStatement t) {
            List<TryStatement.CatchClause> newCatches = new ArrayList<>();
            for (TryStatement.CatchClause cc : t.catchClauses()) {
                newCatches.add(new TryStatement.CatchClause(
                        cc.exceptionType(), cc.varName(),
                        rewriteStatement(cc.body(), switchMapFieldNames)));
            }
            return new TryStatement(
                    rewriteStatement(t.tryBody(), switchMapFieldNames),
                    newCatches,
                    t.finallyBody() != null
                            ? rewriteStatement(t.finallyBody(), switchMapFieldNames) : null);
        }
        if (s instanceof SynchronizedStatement sync) {
            return new SynchronizedStatement(sync.monitorObject(),
                    rewriteStatement(sync.body(), switchMapFieldNames));
        }
        if (s instanceof SwitchStatement sw) {
            return rewriteSwitch(sw, switchMapFieldNames);
        }
        return s;
    }

    /**
     * Check if the switch discriminant uses the SwitchMap pattern and
     * rewrite it if so. Also recurse into case bodies.
     */
    private SwitchStatement rewriteSwitch(SwitchStatement sw, Set<String> switchMapFieldNames) {
        // Try to detect and rewrite the enum switch discriminant.
        Expression newDiscriminant = tryRewriteDiscriminant(sw.discriminant(), switchMapFieldNames);

        // Recurse into case bodies.
        List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            List<Statement> newBody = new ArrayList<>();
            for (Statement cs : cg.body()) {
                newBody.add(rewriteStatement(cs, switchMapFieldNames));
            }
            newCases.add(new SwitchStatement.CaseGroup(cg.labels(), newBody, cg.isDefault()));
        }

        return new SwitchStatement(newDiscriminant, newCases, sw.isExpression());
    }

    /**
     * Try to match the enum switch pattern and extract the enum variable.
     * Pattern: {@code $SwitchMap$XXX[enumValue.ordinal()]}.
     *
     * <p>In the AST this is represented as:
     * <pre>
     *   ArrayAccessExpr(
     *       FieldAccessExpr(target, "$SwitchMap$..."),   // the int array
     *       InvocationExpr(enumVar, "ordinal", [])        // enumVar.ordinal()
     *   )
     * </pre>
     *
     * @return the enum variable expression if matched, otherwise the original expression
     */
    private Expression tryRewriteDiscriminant(Expression discriminant,
                                              Set<String> switchMapFieldNames) {
        if (!(discriminant instanceof ArrayAccessExpr arrayAccess)) {
            return discriminant;
        }

        Expression arrayExpr = arrayAccess.array();
        Expression indexExpr = arrayAccess.index();

        // The array being accessed must reference a $SwitchMap$ field.
        String fieldName = extractFieldName(arrayExpr);
        if (fieldName == null || !isSwitchMapFieldName(fieldName)) {
            return discriminant;
        }

        // The index must be a call to ordinal() with no arguments.
        if (!(indexExpr instanceof InvocationExpr inv)) {
            return discriminant;
        }
        if (!"ordinal".equals(inv.methodName())) {
            return discriminant;
        }
        if (!inv.arguments().isEmpty()) {
            return discriminant;
        }

        // Extract the enum variable from the ordinal() target.
        Expression enumTarget = inv.target();
        if (enumTarget == null) {
            return discriminant;
        }

        return enumTarget;
    }
}
