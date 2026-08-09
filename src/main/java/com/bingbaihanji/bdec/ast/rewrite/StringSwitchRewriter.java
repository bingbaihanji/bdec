package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects javac-generated string switch scaffolding and collapses
 * it back to a native {@code switch (str)} statement.
 *
 * <p>Pattern:
 * <pre>
 *   switch (str.hashCode()) {                    // First switch
 *       case hash1:
 *           if (str.equals("foo")) temp = 0;     // String → int mapping
 *           break;
 *       case hash2:
 *           if (str.equals("bar")) temp = 1;
 *           break;
 *       default: break;
 *   }
 *   switch (temp) {                              // Second switch
 *       case 0: ...body... break;
 *       case 1: ...body... break;
 *       default: ...body... break;
 *   }
 *
 *   → switch (str) {
 *       case "foo": ...body... break;
 *       case "bar": ...body... break;
 *       default: ...body... break;
 *   }
 * </pre>
 *
 * <p>Inspired by CFR's {@code SwitchReWriter} and Vineflower's
 * {@code SwitchProcessor}.
 */
public class StringSwitchRewriter implements RewriteRule {

    @Override
    public String name() {return "string-switch";}

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
                        rewriteBlock(md.body())));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    private Statement rewriteBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement child : bs.statements()) {
                rewritten.add(rewriteBlock(child));
            }
            return detectStringSwitch(new BlockStatement(rewritten));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteBlock(i.thenBranch()),
                    i.elseBranch() != null ? rewriteBlock(i.elseBranch()) : null);
        }
        if (s instanceof LoopStatement l) {
            if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH) {
                return new LoopStatement(l.loopKind(), l.forEachVar(), l.condition(),
                        rewriteBlock(l.body()));
            }
            return new LoopStatement(l.loopKind(), l.condition(), rewriteBlock(l.body()));
        }
        if (s instanceof SwitchStatement sw) {
            // Recursively rewrite nested switches too
            List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
            for (SwitchStatement.CaseGroup cg : sw.cases()) {
                List<Statement> newBody = new ArrayList<>();
                for (Statement cs : cg.body()) {
                    newBody.add(rewriteBlock(cs));
                }
                newCases.add(new SwitchStatement.CaseGroup(cg.labels(), newBody, cg.isDefault()));
            }
            return new SwitchStatement(sw.discriminant(), newCases, sw.isExpression());
        }
        return s;
    }

    /**
     * Walk a block looking for adjacent hashCode-switch + temp-switch
     * patterns and collapse them into a native string switch.
     */
    private Statement detectStringSwitch(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < stmts.size() - 1; i++) {
                // Look for two adjacent SwitchStatements
                if (!(stmts.get(i) instanceof SwitchStatement hashSwitch)) {
                    continue;
                }
                if (!(stmts.get(i + 1) instanceof SwitchStatement tempSwitch)) {
                    continue;
                }

                // Check if first switch is on hashCode()
                HashCodeMatch hashMatch = matchHashCodeSwitch(hashSwitch);
                if (hashMatch == null) {
                    continue;
                }

                // Check if second switch uses the same temp variable
                TempSwitchMatch tempMatch = matchTempSwitch(tempSwitch, hashMatch);
                if (tempMatch == null) {
                    continue;
                }

                // Build the new string switch
                SwitchStatement stringSwitch = buildStringSwitch(hashMatch, tempMatch);
                if (stringSwitch == null) {
                    continue;
                }

                // Replace both switches with the new one
                stmts.remove(i + 1);
                stmts.remove(i);
                stmts.add(i, stringSwitch);
                changed = true;
                break;
            }
        } while (changed);

        return new BlockStatement(stmts);
    }

    /**
     * Detect whether a SwitchStatement matches the hashCode-switch pattern:
     * {@code switch (xxx.hashCode())}.
     */
    private HashCodeMatch matchHashCodeSwitch(SwitchStatement sw) {
        // Check discriminant is: target.hashCode()
        if (!(sw.discriminant() instanceof InvocationExpr inv)) {
            return null;
        }
        if (!"hashCode".equals(inv.methodName())) {
            return null;
        }
        if (inv.target() == null) {
            return null;
        }
        Expression stringVar = inv.target();

        // Collect mappings from each case
        LinkedHashMap<Integer, String> hashToString = new LinkedHashMap<>();
        LinkedHashMap<Integer, Integer> hashToTemp = new LinkedHashMap<>();
        String tempVarName = null;
        SwitchStatement.CaseGroup defaultCase = null;

        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            if (cg.isDefault()) {
                defaultCase = cg;
                continue;
            }

            // Each non-default case should have exactly one int label
            int hashCode = extractIntLabel(cg.labels());
            if (hashCode == Integer.MIN_VALUE) {
                continue; // not an int label, skip
            }

            // Try to extract the string from equals() call in case body
            StringMatch sm = extractStringFromCase(cg, stringVar);
            if (sm == null) {
                continue; // can't extract string mapping from this case
            }

            if (tempVarName == null) {
                tempVarName = sm.tempVar;
            } else if (!tempVarName.equals(sm.tempVar)) {
                return null; // inconsistent temp variable name
            }

            hashToString.put(hashCode, sm.stringValue);
            hashToTemp.put(hashCode, sm.tempValue);
        }

        // Need at least one string mapping to be meaningful
        if (hashToString.isEmpty() || tempVarName == null) {
            return null;
        }

        return new HashCodeMatch(stringVar, hashToString, hashToTemp, tempVarName, defaultCase);
    }

    /**
     * Extract the string literal from a hashCode switch case body.
     * Pattern: {@code if (str.equals("literal")) tempVar = intVal;}
     * or the IfStatement may be the only statement in the case body.
     */
    private StringMatch extractStringFromCase(SwitchStatement.CaseGroup cg, Expression stringVar) {
        for (Statement s : cg.body()) {
            if (!(s instanceof IfStatement ifStmt)) {
                continue;
            }
            StringMatch sm = matchEqualsAssign(ifStmt, stringVar);
            if (sm != null) {
                return sm;
            }
        }
        return null;
    }

    /**
     * Match: {@code if (str.equals("literal")) tempVar = intVal;}
     */
    private StringMatch matchEqualsAssign(IfStatement ifStmt, Expression expectedTarget) {
        // Condition: str.equals("literal")
        if (!(ifStmt.condition() instanceof InvocationExpr condInv)) {
            return null;
        }
        if (!"equals".equals(condInv.methodName())) {
            return null;
        }
        if (condInv.arguments().size() != 1) {
            return null;
        }
        if (!(condInv.arguments().get(0) instanceof LitExpr strLit)) {
            return null;
        }
        if (!(strLit.value() instanceof String strValue)) {
            return null;
        }

        // Verify target matches (the String variable)
        if (condInv.target() == null) {
            return null;
        }
        // The target could be a VarExpr with the same name as expectedTarget
        if (expectedTarget instanceof VarExpr ev) {
            if (!(condInv.target() instanceof VarExpr tv)) {
                return null;
            }
            if (!ev.name().equals(tv.name())) {
                return null;
            }
        }
        // If expectedTarget is not a VarExpr, just compare structurally
        // (for non-VarExpr patterns, we skip structural comparison for now)

        // Then branch: assign int literal to temp var
        AssignmentResult ar = extractAssignment(ifStmt.thenBranch());
        if (ar == null) {
            return null;
        }

        return new StringMatch(strValue, ar.varName, ar.intValue);
    }

    /**
     * Extract: {@code tempVar = intVal;} from a statement.
     * The statement may be wrapped in a BlockStatement.
     */
    private AssignmentResult extractAssignment(Statement stmt) {
        if (stmt instanceof BlockStatement bs) {
            // Look for the assignment in the block
            for (Statement s : bs.statements()) {
                AssignmentResult ar = extractAssignment(s);
                if (ar != null) {
                    return ar;
                }
            }
            return null;
        }

        if (!(stmt instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.target() instanceof VarExpr ve)) {
            return null;
        }
        if (!(assign.value() instanceof LitExpr lit)) {
            return null;
        }
        if (!(lit.value() instanceof Integer intVal)) {
            return null;
        }

        return new AssignmentResult(ve.name(), intVal);
    }

    /**
     * Extract a single int label from a case label list.
     * Returns Integer.MIN_VALUE if no int label is found.
     */
    private int extractIntLabel(List<Expression> labels) {
        for (Expression label : labels) {
            if (label instanceof LitExpr lit && lit.value() instanceof Integer intVal) {
                return intVal;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Verify that the temp switch uses the same temp variable as the hashCode switch.
     */
    private TempSwitchMatch matchTempSwitch(SwitchStatement sw, HashCodeMatch hashMatch) {
        // Second switch discriminant should be just the temp variable
        if (!(sw.discriminant() instanceof VarExpr ve)) {
            return null;
        }
        if (!hashMatch.tempVarName.equals(ve.name())) {
            return null;
        }

        // Collect case groups by their int label
        Map<Integer, SwitchStatement.CaseGroup> intToCase = new LinkedHashMap<>();
        SwitchStatement.CaseGroup defaultCase = null;

        for (SwitchStatement.CaseGroup cg : sw.cases()) {
            if (cg.isDefault()) {
                defaultCase = cg;
                continue;
            }
            int labelInt = extractIntLabel(cg.labels());
            if (labelInt != Integer.MIN_VALUE) {
                intToCase.put(labelInt, cg);
            }
        }

        return new TempSwitchMatch(ve.name(), intToCase, defaultCase);
    }

    /**
     * Build the new string switch: {@code switch (str) { case "foo": ... case "bar": ... }}.
     */
    private SwitchStatement buildStringSwitch(HashCodeMatch hashMatch, TempSwitchMatch tempMatch) {
        // Build mapping: temp int value → string literal
        Map<Integer, String> tempToString = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : hashMatch.hashToTemp.entrySet()) {
            int hashVal = entry.getKey();
            int tempVal = entry.getValue();
            String strVal = hashMatch.hashToString.get(hashVal);
            if (strVal != null) {
                tempToString.put(tempVal, strVal);
            }
        }

        if (tempToString.isEmpty()) {
            return null;
        }

        // Build case groups with string labels
        List<SwitchStatement.CaseGroup> newCases = new ArrayList<>();
        JavaType stringType = JavaType.classType("java/lang/String");

        for (Map.Entry<Integer, String> entry : tempToString.entrySet()) {
            int tempVal = entry.getKey();
            String strVal = entry.getValue();
            SwitchStatement.CaseGroup origCase = tempMatch.intToCase().get(tempVal);
            if (origCase == null) {
                continue; // temp value has no matching case in second switch
            }

            // Replace int labels with string literal labels
            List<Expression> newLabels = List.of(new LitExpr(strVal, stringType));
            newCases.add(new SwitchStatement.CaseGroup(newLabels, origCase.body(), false));
        }

        // If there are temp cases that we couldn't remap, skip them (they're unreachable)

        // Find default case: use temp switch's default if available
        SwitchStatement.CaseGroup defCase = tempMatch.defaultCase();
        if (defCase != null) {
            newCases.add(new SwitchStatement.CaseGroup(List.of(), defCase.body(), true));
        }

        // New discriminant is the original String variable (not hashCode target)
        return new SwitchStatement(hashMatch.stringVar, newCases);
    }

    /** Match result for the hashCode-switch pattern. */
    private static class HashCodeMatch {

        final Expression stringVar;              // the String variable

        final LinkedHashMap<Integer, String> hashToString; // hashCode → string literal

        final LinkedHashMap<Integer, Integer> hashToTemp; // hashCode → temp int value

        final String tempVarName;

        final SwitchStatement.CaseGroup defaultCase; // may be null

        HashCodeMatch(Expression stringVar,
                      LinkedHashMap<Integer, String> hashToString,
                      LinkedHashMap<Integer, Integer> hashToTemp,
                      String tempVarName,
                      SwitchStatement.CaseGroup defaultCase) {
            this.stringVar = stringVar;
            this.hashToString = hashToString;
            this.hashToTemp = hashToTemp;
            this.tempVarName = tempVarName;
            this.defaultCase = defaultCase;
        }
    }

    /** Match result for the temp-switch pattern. */
    private record TempSwitchMatch(
            String tempVarName,
            Map<Integer, SwitchStatement.CaseGroup> intToCase,
            SwitchStatement.CaseGroup defaultCase) {}

    /** Extracted string from a hashCode case body. */
    private static class StringMatch {

        final String stringValue;

        final String tempVar;

        final int tempValue;

        StringMatch(String stringValue, String tempVar, int tempValue) {
            this.stringValue = stringValue;
            this.tempVar = tempVar;
            this.tempValue = tempValue;
        }
    }

    /** Extracted assignment result. */
    private static class AssignmentResult {

        final String varName;

        final int intValue;

        AssignmentResult(String varName, int intValue) {
            this.varName = varName;
            this.intValue = intValue;
        }
    }
}
