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
 * Detects Iterator-based and array-indexed loops and converts them
 * to Java {@code for (E e : collection)} enhanced for-each loops.
 *
 * <p>Pattern (Collection):
 * <pre>
 *   Iterator iter = collection.iterator();
 *   while (iter.hasNext()) {
 *       E element = iter.next();
 *       // body
 *   }
 * </pre>
 *
 * <p>Pattern (Array):
 * <pre>
 *   for (int i = 0; i < arr.length; i++) {
 *       E element = arr[i];
 *       // body
 *   }
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
            return detectForEach(new BlockStatement(
                    bs.statements().stream().map(this::rewriteStatement).toList()));
        }
        return s;
    }

    /**
     * Walk a BlockStatement looking for iterator→for-each patterns.
     * When found, replace the iterator declaration + while loop with a for-each loop.
     */
    private Statement detectForEach(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        for (int i = 0; i < stmts.size() - 1; i++) {
            Statement s = stmts.get(i);

            // Pattern: Iterator<E> iter = collection.iterator()
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof AssignExpr assign
                    && assign.value() instanceof InvocationExpr inv
                    && "iterator".equals(inv.methodName())
                    && inv.target() != null) {

                String iterVar = assign.target() instanceof VarExpr vx ? vx.name() : null;
                Expression collection = inv.target();

                // Find while(iter.hasNext()) loop
                if (i + 1 < stmts.size() && stmts.get(i + 1) instanceof LoopStatement loop
                        && loop.loopKind() == LoopStatement.LoopKind.WHILE) {

                    ForEachMatch match = matchWhileLoop(loop, iterVar, collection);
                    if (match != null) {
                        // Replace: remove iterator decl and while loop, insert for-each
                        stmts.remove(i + 1); // while loop
                        stmts.remove(i);     // iterator decl
                        stmts.add(i, new LoopStatement(LoopStatement.LoopKind.FOR_EACH,
                                null, loop.condition(), match.elementExpr,
                                match.loopBody));
                        return new BlockStatement(stmts);
                    }
                }
            }

            // Pattern: array for-loop
            if (s instanceof LoopStatement loop
                    && loop.loopKind() == LoopStatement.LoopKind.FOR
                    && loop.initExpr() instanceof AssignExpr initAssign) {
                ForEachMatch match = matchArrayForLoop(loop);
                if (match != null) {
                    stmts.set(i, new LoopStatement(LoopStatement.LoopKind.FOR_EACH,
                            null, loop.condition(), match.elementExpr, match.loopBody));
                    return new BlockStatement(stmts);
                }
            }
        }
        return bs;
    }

    /** Try to match a while(iter.hasNext()) loop as a for-each pattern. */
    private ForEachMatch matchWhileLoop(LoopStatement loop, String iterVar, Expression collection) {
        if (!(loop.body() instanceof BlockStatement bodyBs)) return null;

        List<Statement> bodyStmts = bodyBs.statements();
        if (bodyStmts.isEmpty()) return null;

        // First statement should be: Type element = iter.next()
        Statement first = bodyStmts.get(0);
        if (first instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.value() instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr vx
                && vx.name().equals(iterVar)) {

            Expression elementExpr = assign.target();
            // Build new loop body without the first statement
            List<Statement> newBody = new ArrayList<>(bodyStmts);
            newBody.remove(0);
            Statement newLoopBody = newBody.size() == 1 ? newBody.get(0) : new BlockStatement(newBody);

            // Create for-each expression: varName
            return new ForEachMatch(collection, elementExpr, newLoopBody);
        }
        return null;
    }

    /** Try to match an array-indexed for loop as for-each. */
    private ForEachMatch matchArrayForLoop(LoopStatement loop) {
        if (!(loop.body() instanceof BlockStatement bodyBs)) return null;
        List<Statement> bodyStmts = bodyBs.statements();
        if (bodyStmts.isEmpty()) return null;

        // Check first statement is: Type element = arr[i]
        Statement first = bodyStmts.get(0);
        if (first instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.value() instanceof FieldAccessExpr fae
                && "length".equals(fae.fieldName())) {
            // Too complex pattern — skip for now
        }

        return null; // Array for-each is complex, defer to later iteration
    }

    private static class ForEachMatch {
        final Expression collectionExpr;
        final Expression elementExpr;
        final Statement loopBody;

        ForEachMatch(Expression c, Expression e, Statement b) {
            this.collectionExpr = c;
            this.elementExpr = e;
            this.loopBody = b;
        }
    }
}
