package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.util.ClassNames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 匿名类内联重写器——将反编译为独立嵌套类的匿名类(如 TestClass2$1)
 * 内联回实例化处,还原 {@code new InterfaceA() { ... }} 形式.
 *
 * <p>检测模式:
 * <pre>
 *   X$N v = new X$N(this, args...);
 * </pre>
 * 其中 X$N 是编译单元中带数字后缀的匿名类 TypeDeclaration.
 * 转换为:
 * <pre>
 *   父类型 v = new 父类型(args...) { X$N 的成员 };
 * </pre>
 * 并移除 X$N 的独立 TypeDeclaration(匿名类无源码级名称).
 */
public class AnonymousClassRewriter implements RewriteRule {

    /** 剥离类型引用中的泛型参数(如 "List<String>" → "List"),供父类型解析用. */
    private static String stripGenericArgs(String ref) {
        int lt = ref.indexOf('<');
        return lt >= 0 ? ref.substring(0, lt) : ref;
    }

    @Override
    public String name() {return "anonymous-class";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        // 收集匿名类 TypeDeclaration(名称含 $数字).
        // 匿名类可能作为顶层类型或嵌套在主类内部的成员存在.
        Map<String, TypeDeclaration> anonTypes = new HashMap<>();
        List<TypeDeclaration> kept = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            if (ClassNames.isAnonymousClassName(td.simpleName())) {
                anonTypes.put(td.simpleName(), td);
            } else {
                collectNestedAnonTypes(td, anonTypes);
                kept.add(td);
            }
        }
        if (anonTypes.isEmpty()) {
            return unit;
        }

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : kept) {
            types.add(rewriteType(td, anonTypes));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types,
                unit.innerClassNames());
    }

    /** 收集嵌套在主类成员中的匿名类 TypeDeclaration */
    private void collectNestedAnonTypes(TypeDeclaration td,
                                        Map<String, TypeDeclaration> anonTypes) {
        for (AstNode m : td.children()) {
            if (m instanceof TypeDeclaration nested) {
                if (ClassNames.isAnonymousClassName(nested.simpleName())) {
                    anonTypes.put(nested.simpleName(), nested);
                } else {
                    collectNestedAnonTypes(nested, anonTypes);
                }
            }
        }
    }

    private TypeDeclaration rewriteType(TypeDeclaration td,
                                        Map<String, TypeDeclaration> anonTypes) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                Statement newBody = rewriteStatement(md.body(), anonTypes);
                members.add(withBody(md, newBody instanceof BlockStatement bs ? bs : newBody));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 递归遍历语句,内联匿名类实例化 */
    private Statement rewriteStatement(Statement s,
                                       Map<String, TypeDeclaration> anonTypes) {
        if (s instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>();
            for (Statement c : bs.statements()) {
                Statement r = rewriteStatement(c, anonTypes);
                if (r instanceof VariableDeclaration vd
                        && vd.initializer() instanceof NewExpr ne
                        && isAnonymousNew(ne)) {
                    TypeDeclaration anon = anonTypes.get(simpleTypeName(ne.instantiatedType()));
                    if (anon != null) {
                        stmts.add(new VariableDeclaration(
                                anonBaseType(anon), vd.name(),
                                buildAnonymousNew(ne, anon)));
                        continue;
                    }
                }
                stmts.add(r);
            }
            return new BlockStatement(stmts);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch(), anonTypes),
                    i.elseBranch() != null
                            ? rewriteStatement(i.elseBranch(), anonTypes) : null);
        }
        if (s instanceof LoopStatement l) {
            return withLoopBody(l, rewriteStatement(l.body(), anonTypes));
        }
        return s;
    }

    /** NewExpr 是否为匿名类实例化(类型名含 $数字) */
    private boolean isAnonymousNew(NewExpr ne) {
        String name = simpleTypeName(ne.instantiatedType());
        return ClassNames.isAnonymousClassName(name);
    }

    private String simpleTypeName(JavaType t) {
        if (t == null || t.internalName() == null) {
            return "";
        }
        String internal = t.internalName();
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    /** 匿名类的父类型(接口或父类) */
    private JavaType anonBaseType(TypeDeclaration anon) {
        if (!anon.interfaceNames().isEmpty()) {
            return JavaType.classType(stripGenericArgs(anon.interfaceNames().getFirst()));
        }
        if (anon.superName() != null && !anon.superName().isEmpty()
                && !"java/lang/Object".equals(anon.superName())) {
            return JavaType.classType(stripGenericArgs(anon.superName()));
        }
        return JavaType.classType("java/lang/Object");
    }

    /** 构建匿名类实例化:父类型 + 去 this$0 的参数 + 匿名类体 */
    private NewExpr buildAnonymousNew(NewExpr ne, TypeDeclaration anon) {
        // 构造参数去掉首个 this$0 外围引用参数
        List<Expression> args = new ArrayList<>(ne.constructorArgs());
        if (!args.isEmpty() && args.getFirst() instanceof VarExpr v
                && ("this".equals(v.name()) || v.name().startsWith("this$"))) {
            args.removeFirst();
        }
        // 匿名类体成员:方法与字段声明(排除 this$0 字段与构造器)
        List<AstNode> body = new ArrayList<>();
        for (AstNode m : anon.children()) {
            if (m instanceof MethodDeclaration md && md.name() != null
                    && !md.name().equals(anon.simpleName())) {
                body.add(m);
            } else if (m instanceof FieldDeclaration fd
                    && !fd.name().startsWith("this$")) {
                body.add(m);
            }
        }
        return new NewExpr(anonBaseType(anon), List.of(), args, body, List.of(),
                ne.typeAnnotations());
    }
}
