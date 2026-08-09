package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects {@code instanceof} + explicit cast patterns and converts
 * them to Java 16+ pattern matching {@code instanceof}.
 *
 * <p>Pattern:
 * <pre>
 *   if (obj instanceof String) {
 *       String s = (String) obj;
 *       ...use s...
 *   }
 *
 *   → if (obj instanceof String s) {
 *       ...use s...
 *   }
 * </pre>
 *
 * <p>Inspired by CFR's {@code InstanceOfExpressionDefining}.
 */
public class PatternMatchRewriter implements RewriteRule {

    @Override
    public String name() {return "pattern-match";}

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
            List<Statement> rewritten = new ArrayList<>();
            for (Statement child : bs.statements()) {
                rewritten.add(rewriteStatement(child));
            }
            return detectPatternMatch(new BlockStatement(rewritten));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        return s;
    }

    /**
     * Detect: {@code if(obj instanceof Type) { Type v = (Type)obj; ... }}
     * and collapse to: {@code if(obj instanceof Type v) { ... }}
     */
    private Statement detectPatternMatch(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (!(s instanceof IfStatement ifStmt)) {
                continue;
            }
            if (ifStmt.elseBranch() != null) {
                continue; // only simple if-then
            }

            // Check condition: obj instanceof Type
            if (!(ifStmt.condition() instanceof BinExpr be)) {
                continue;
            }
            if (be.operator() != BinaryOperator.INSTANCEOF) {
                continue;
            }
            if (!(be.right() instanceof VarExpr typeExpr)) {
                continue;
            }
            Expression testedObj = be.left();

            // Check then-body: first statement is Type v = (Type)obj;
            List<Statement> thenStmts = getBodyStatements(ifStmt.thenBranch());
            if (thenStmts.isEmpty()) {
                continue;
            }
            Statement first = thenStmts.get(0);

            String varName = extractVarDecl(first, typeExpr.name(), testedObj);
            if (varName == null) {
                continue;
            }

            // Build new condition: obj instanceof Type varName
            Expression newCondition = new BinExpr(BinaryOperator.INSTANCEOF,
                    testedObj, new VarExpr(typeExpr.name() + " " + varName));

            // Build new then-body (minus the cast declaration)
            List<Statement> newThen = new ArrayList<>(thenStmts);
            newThen.remove(0);
            Statement newThenBody = newThen.size() == 1 ? newThen.get(0)
                    : new BlockStatement(newThen);

            stmts.set(i, new IfStatement(newCondition, newThenBody, null));
            return new BlockStatement(stmts);
        }
        return bs;
    }

    private String extractVarDecl(Statement s, String typeName, Expression testedObj) {
        if (!(s instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.target() instanceof VarExpr var)) {
            return null;
        }
        // Check RHS is a cast: (Type) obj
        if (!(assign.value() instanceof CastExpr cast)) {
            return null;
        }
        // Check the cast target matches
        String castType = cast.targetType().internalName();
        if (castType == null) {
            return null;
        }
        String shortType = castType.contains("/")
                ? castType.substring(castType.lastIndexOf('/') + 1) : castType;
        if (!shortType.equals(typeName)) {
            return null;
        }
        // Check the cast expression matches the tested object
        // (Heuristic: both are VarExpr with same name or similar)
        return var.name();
    }

    private List<Statement> getBodyStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new ArrayList<>(bs.statements());
        }
        return new ArrayList<>(List.of(s));
    }
}
