package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p>此外修复"类型变量擦除强转"伪影:lambda 合成方法内类型变量不在作用域,
 * javac 把泛型方法实参强转到类型变量的擦除类(如 {@code <K extends Comparable>}
 * 的 {@code result.put(e.getKey(), ...)} 在 lambda 内发射 {@code checkcast
 * Comparable}).内联回声明泛型方法的源码后,擦除类强转无法赋给类型变量参数
 * ({@code Comparable} 不能转 {@code K}).识别"实参强转到在作用域类型变量的
 * 擦除类 + 接收者类型实参在该位置绑定同一类型变量"后,把强转重定向回类型变量
 * ({@code (Comparable) e.getKey()} → {@code (K) e.getKey()},参照 vineflower).</p>
 */
public class RedundantCastRewriter implements RewriteRule {

    /** 解析类型参数串,建立"擦除类简单名 → 类型变量名"映射.
     *  {@code K extends Comparable<? super K>} → Comparable→K;无边界(V)不记录. */
    private static void addErasures(Map<String, String> map, List<String> typeParams) {
        if (typeParams == null) {
            return;
        }
        for (String tp : typeParams) {
            int ext = tp.indexOf("extends");
            if (ext < 0) {
                continue;
            }
            String name = tp.substring(0, ext).trim();
            if (name.isEmpty()) {
                continue;
            }
            String bound = tp.substring(ext + "extends".length()).trim();
            int amp = bound.indexOf('&');
            if (amp >= 0) {
                bound = bound.substring(0, amp).trim();
            }
            int lt = bound.indexOf('<');
            if (lt >= 0) {
                bound = bound.substring(0, lt).trim();
            }
            int dot = bound.lastIndexOf('.');
            String simple = dot >= 0 ? bound.substring(dot + 1) : bound;
            if (simple.isEmpty() || "Object".equals(simple)) {
                continue;
            }
            // 仅当擦除类唯一对应一个类型变量时记录(多变量同擦除→歧义,保守跳过)
            String prev = map.put(simple, name);
            if (prev != null && !prev.equals(name)) {
                map.remove(simple);
            }
        }
    }

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
        // 类级类型参数亦在作用域(方法体内可引用类的类型变量)
        Map<String, String> erasures = new HashMap<>();
        addErasures(erasures, td.typeParameters());
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                Map<String, String> mErasures = new HashMap<>(erasures);
                addErasures(mErasures, md.typeParameters());
                members.add(withBody(md, md.body() != null
                        ? rewriteStatement(md.body(), mErasures, md) : null));
            } else if (m instanceof com.bingbaihanji.bdec.ast.stmt.FieldDeclaration fd
                    && fd.initializer() != null) {
                members.add(withInitializer(fd,
                        new CastCleaner(erasures).transformExpr(fd.initializer())));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    private Statement rewriteStatement(Statement s, Map<String, String> erasures,
                                       MethodDeclaration md) {
        return (Statement) new CastCleaner(erasures).seedParams(md).visitStatement(s, null);
    }

    /** 递归清理冗余强转 + 类型变量擦除强转重定向的转换器. */
    private static final class CastCleaner extends AstTransformer {

        /** 擦除类简单名 → 类型变量名 */
        private final Map<String, String> erasureToTypeVar;

        /** 变量名 → 声明类型(局部变量声明 + 方法参数) */
        private final Map<String, JavaType> varTypes = new HashMap<>();

        CastCleaner(Map<String, String> erasureToTypeVar) {
            this.erasureToTypeVar = erasureToTypeVar;
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

        /** 将方法参数类型预置进变量作用域. */
        CastCleaner seedParams(MethodDeclaration md) {
            String[] names = md.parameterNames();
            JavaType[] types = md.parameterTypes();
            if (names != null && types != null) {
                for (int i = 0; i < names.length && i < types.length; i++) {
                    varTypes.put(names[i], types[i]);
                }
            }
            return this;
        }

        @Override
        protected Expression transformCast(CastExpr e) {
            Expression op = e.operand();
            JavaType opType = operandType(op);
            if (opType != null && e.targetType() != null
                    && opType.kind() == TypeKind.CLASS
                    && e.targetType().kind() == TypeKind.CLASS
                    && opType.internalName() != null
                    && e.targetType().internalName() != null
                    && opType.internalName().equals(e.targetType().internalName())) {
                // 操作数静态类型已是目标类型:强转冗余,递归清理操作数内的强转后返回
                return transformExpr(op);
            }
            return super.transformCast(e);
        }

        @Override
        protected Statement transformVarDecl(VariableDeclaration s) {
            if (s.type() != null) {
                varTypes.put(s.name(), s.type());
            }
            return super.transformVarDecl(s);
        }

        @Override
        protected Expression transformInvocation(InvocationExpr e) {
            Expression tgt = e.target() != null ? transformExpr(e.target()) : null;
            List<Expression> args = transformExprList(e.arguments());
            List<Expression> newArgs = retargetErasureCasts(tgt, args);
            return (tgt != e.target() || newArgs != e.arguments())
                    ? new InvocationExpr(tgt, e.methodName(), newArgs, e.returnType()) : e;
        }

        /** 把实参中"类型变量擦除类强转"重定向回类型变量(见类文档). */
        private List<Expression> retargetErasureCasts(Expression target, List<Expression> args) {
            if (erasureToTypeVar.isEmpty() || args.isEmpty()
                    || !(target instanceof VarExpr ve)) {
                return args;
            }
            JavaType recvType = varTypes.get(ve.name());
            if (recvType == null || recvType.kind() != TypeKind.CLASS
                    || recvType.typeArguments().isEmpty()) {
                return args;
            }
            List<Expression> newArgs = null;
            for (int i = 0; i < args.size(); i++) {
                Expression a = args.get(i);
                if (!(a instanceof CastExpr ce)) {
                    continue;
                }
                JavaType castType = ce.targetType();
                if (castType == null || castType.kind() != TypeKind.CLASS
                        || castType.internalName() == null) {
                    continue;
                }
                String castSimple = com.bingbaihanji.bdec.ast.TypeReferenceUtil
                        .simpleName(castType.internalName());
                String tvName = erasureToTypeVar.get(castSimple);
                if (tvName == null) {
                    continue;
                }
                // 按实参位置绑定:该位置接收者类型实参须恰为该类型变量
                // (如 result.put((Comparable) x, y) 的 result 为 LinkedHashMap<K,V>,
                // put(K,V) 的 K 与接收者首个类型实参 K 按位置对齐).
                if (i >= recvType.typeArguments().size()) {
                    continue;
                }
                JavaType boundVar = recvType.typeArguments().get(i);
                if (boundVar.kind() != TypeKind.TYPE_VARIABLE
                        || !tvName.equals(boundVar.internalName())) {
                    continue;
                }
                if (newArgs == null) {
                    newArgs = new ArrayList<>(args);
                }
                newArgs.set(i, new CastExpr(boundVar, ce.operand(), ce.typeAnnotations()));
            }
            return newArgs != null ? newArgs : args;
        }
    }
}
