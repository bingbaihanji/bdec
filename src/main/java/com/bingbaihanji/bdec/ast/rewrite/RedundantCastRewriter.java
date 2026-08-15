package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 冗余强转抑制(晚段,在 for-each/模式匹配等消费强转的重写器之后运行).
 *
 * <p>字节码中的 CHECKCAST 可能来自源码的显式强转(即使操作数静态类型已相同,
 * javac 也会为泛型表达式发射,如 {@code (String) l.get(0)} 中 {@code l} 为
 * {@code List<String>}).泛型推断(实例方法返回 + 工厂方法)使操作数的静态类型
 * 可确定时,强转冗余可移除:{@code (String) l.get(0)} → {@code l.get(0)},
 * {@code (Integer) m.get("a")} → {@code m.get("a")}.raw 集合的
 * {@code (String) raw.get(0)} 操作数为 {@code Object} → 保留.</p>
 *
 * <p>必须晚于 {@link ForEachRewriter}:for-each 重建依赖 {@code ((T)it.next())}
 * 模式识别元素类型,提前抑制会破坏元素类型还原.</p>
 */
public class RedundantCastRewriter implements RewriteRule {

    @Override
    public String name() {return "redundant-cast";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<com.bingbaihanji.bdec.ast.AstNode> members = new ArrayList<>();
        for (com.bingbaihanji.bdec.ast.AstNode m : td.children()) {
            if (m instanceof com.bingbaihanji.bdec.ast.stmt.MethodDeclaration md) {
                members.add(withBody(md, md.body() != null
                        ? rewriteStatement(md.body()) : null));
            } else if (m instanceof com.bingbaihanji.bdec.ast.stmt.FieldDeclaration fd
                    && fd.initializer() != null) {
                members.add(withInitializer(fd,
                        new CastCleaner().transformExpr(fd.initializer())));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    private Statement rewriteStatement(Statement s) {
        return (Statement) new CastCleaner().visitStatement(s, null);
    }

    /** 递归清理冗余强转的转换器. */
    private static final class CastCleaner extends AstTransformer {

        @Override
        protected Expression transformCast(CastExpr e) {
            Expression op = e.operand();
            JavaType opType = operandType(op);
            if (opType != null && e.targetType() != null
                    && opType.kind() == TypeKind.CLASS
                    && e.targetType().kind() == TypeKind.CLASS
                    && opType.internalName() != null
                    && opType.internalName().equals(e.targetType().internalName())) {
                // 操作数静态类型已是目标类型:强转冗余,递归清理操作数内的强转后返回
                return transformExpr(op);
            }
            return super.transformCast(e);
        }

        /** 表达式的静态类型(用于冗余判定;无法确定返回 null 则不强转抑制). */
        private static JavaType operandType(Expression e) {
            if (e instanceof InvocationExpr inv) {
                return inv.returnType();
            }
            if (e instanceof NewExpr ne) {
                return ne.instantiatedType();
            }
            if (e instanceof CastExpr ce) {
                return ce.targetType();
            }
            if (e instanceof LitExpr lit) {
                return lit.type();
            }
            if (e instanceof VarExpr ve) {
                return ve.inferredType();
            }
            if (e instanceof FieldAccessExpr fa) {
                return fa.inferredType();
            }
            return null;
        }
    }
}
