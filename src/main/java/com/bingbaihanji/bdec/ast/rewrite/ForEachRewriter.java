package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects Iterator-based loops and converts them to Java
 * {@code for (E element : collection)} enhanced for-each loops.
 *
 * <p>Pattern:
 * <pre>
 *   Iterator iter = collection.iterator();
 *   while (iter.hasNext()) { E element = iter.next(); ...body... }
 *
 *   → for (E element : collection) { ...body... }
 * </pre>
 *
 * <p>Inspired by CFR's {@code IterLoopRewriter}.
 */
public class ForEachRewriter implements RewriteRule {

    @Override
    public String name() { return "for-each"; }

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
            return detectForEach(new BlockStatement(rewritten));
        }
        if (s instanceof LoopStatement ls) {
            return new LoopStatement(ls.loopKind(), ls.initExpr(),
                    ls.condition(), ls.incrExpr(), rewriteBlock(ls.body()));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteBlock(i.thenBranch()),
                    i.elseBranch() != null ? rewriteBlock(i.elseBranch()) : null);
        }
        return s;
    }

    /**
     * Walk a block looking for adjacent iterator-declaration + while-loop
     * patterns and collapse them into for-each loops.
     */
    private Statement detectForEach(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < stmts.size() - 1; i++) {
                Statement s = stmts.get(i);
                // Look for: ExpressionStatement containing iterator() assignment
                ForEachCandidate candidate = matchIteratorDecl(s);
                if (candidate == null) continue;

                // Check next statement is a while-loop
                if (!(stmts.get(i + 1) instanceof LoopStatement loop)
                        || loop.loopKind() != LoopStatement.LoopKind.WHILE) continue;

                // Match: while(iter.hasNext())
                ForEachCandidate result = matchWhileLoop(loop, candidate);
                if (result == null) continue;

                // Build for-each loop
                LoopStatement forEach = new LoopStatement(
                        LoopStatement.LoopKind.FOR_EACH,
                        result.elementVar,
                        candidate.iterableExpr,
                        result.body);

                // Replace iterator decl + while loop with for-each
                stmts.remove(i + 1);
                stmts.remove(i);
                stmts.add(i, forEach);
                changed = true;
                break;
            }
        } while (changed);

        return new BlockStatement(stmts);
    }

    /** Match: {@code Iterator iter = collection.iterator();} */
    private ForEachCandidate matchIteratorDecl(Statement s) {
        if (!(s instanceof ExpressionStatement es)) return null;
        if (!(es.expression() instanceof AssignExpr assign)) return null;
        if (!(assign.value() instanceof InvocationExpr inv)) return null;
        if (!"iterator".equals(inv.methodName())) return null;
        if (inv.target() == null) return null;

        // Extract variable name
        String varName = null;
        if (assign.target() instanceof VarExpr vx) {
            varName = vx.name();
        }
        if (varName == null) return null;

        return new ForEachCandidate(varName, inv.target());
    }

    /** Match: {@code while(iter.hasNext()) { E e = iter.next(); ... }} */
    private ForEachCandidate matchWhileLoop(LoopStatement loop, ForEachCandidate candidate) {
        // Check condition: iter.hasNext()
        if (!(loop.condition() instanceof InvocationExpr condInv)) return null;
        if (!"hasNext".equals(condInv.methodName())) return null;
        if (!(condInv.target() instanceof VarExpr var)) return null;
        if (!candidate.iterVar.equals(var.name())) return null;

        // Check body: first statement is E element = iter.next()
        List<Statement> bodyStmts = getBodyStatements(loop.body());
        if (bodyStmts.isEmpty()) return null;

        Statement first = bodyStmts.get(0);
        if (!(first instanceof ExpressionStatement es)) return null;
        if (!(es.expression() instanceof AssignExpr assign)) return null;
        if (!(assign.value() instanceof InvocationExpr nextInv)) return null;
        if (!"next".equals(nextInv.methodName())) return null;
        if (!(nextInv.target() instanceof VarExpr nextVar)) return null;
        if (!candidate.iterVar.equals(nextVar.name())) return null;

        // Build new body (minus the next() call)
        List<Statement> newBodyStmts = new ArrayList<>(bodyStmts);
        newBodyStmts.remove(0);
        Statement newBody;
        if (newBodyStmts.isEmpty()) {
            newBody = new BlockStatement(List.of());
        } else if (newBodyStmts.size() == 1) {
            newBody = newBodyStmts.get(0);
        } else {
            newBody = new BlockStatement(newBodyStmts);
        }

        return new ForEachCandidate(candidate.iterVar, candidate.iterableExpr,
                assign.target(), newBody);
    }

    private List<Statement> getBodyStatements(Statement s) {
        if (s instanceof BlockStatement bs) return new ArrayList<>(bs.statements());
        return new ArrayList<>(List.of(s));
    }

    private static class ForEachCandidate {
        final String iterVar;
        final Expression iterableExpr;
        final Expression elementVar; // null for decl pattern
        final Statement body;       // null for decl pattern

        // Iterator declaration pattern
        ForEachCandidate(String iterVar, Expression iterableExpr) {
            this.iterVar = iterVar;
            this.iterableExpr = iterableExpr;
            this.elementVar = null;
            this.body = null;
        }

        // While-loop pattern
        ForEachCandidate(String iterVar, Expression iterableExpr,
                         Expression elementVar, Statement body) {
            this.iterVar = iterVar;
            this.iterableExpr = iterableExpr;
            this.elementVar = elementVar;
            this.body = body;
        }
    }
}
