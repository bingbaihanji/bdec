package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 成员内部类合成外围引用(this$0)清理重写器.
 *
 * <p>参考 CFR 的 {@code CodeAnalyserWholeClass.removeInnerClassOuterThis()}
 * 与 Vineflower 的 {@code NestedClassProcessor.getMaskLocalVars()} 设计:
 * 非静态内部类在字节码中携带 {@code final Outer this$0} 合成字段与
 * {@code Inner(Outer this$0)} 合成构造参数,源码中二者都不可见,由隐式
 * {@code Outer.this} 承担.本重写器在成员内部类被反编译为嵌套类型声明之后运行,
 * 消除这些合成痕迹:</p>
 *
 * <ol>
 *   <li>移除 {@code this$X} 合成字段声明;</li>
 *   <li>清理构造函数:去掉 {@code this$X} 参数与 {@code this.this$X = this$X} 赋值;</li>
 *   <li>方法体内 {@code this$0.field} 重写为直接字段访问 {@code field}
 *       (Java 11+ 基于 nestmate 的私有访问,无需合成 access$ 方法);</li>
 *   <li>外层类实例化处 {@code new Inner(this, ...)} 去掉外围 {@code this} 参数.</li>
 * </ol>
 *
 * <p>捕获局部变量({@code val$X})由 {@link AnonymousClassRewriter} 在匿名类内联时
 * 一并还原;命名局部类若捕获局部变量,其 {@code val$X} 字段保留(仍可编译),留待
 * 后续"局部类移入方法内"的完整实现.</p>
 */
public class InnerClassRewriter implements RewriteRule {

    @Override
    public String name() {return "inner-class";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        // 收集每个嵌套类的外围 this 合成参数个数(0 或 1),用于实例化处去参
        Map<String, Integer> outerThisCount = new HashMap<>();
        for (TypeDeclaration td : unit.types()) {
            collectOuterThisCount(td, outerThisCount);
        }
        if (outerThisCount.isEmpty()) {
            return unit;
        }
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, outerThisCount));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types,
                unit.innerClassNames());
    }

    /** 递归收集各嵌套类型的外围 this 合成参数个数(含 this$X 字段记 1,否则 0). */
    private void collectOuterThisCount(TypeDeclaration td, Map<String, Integer> counts) {
        for (AstNode m : td.children()) {
            if (m instanceof TypeDeclaration nested) {
                int count = findThisField(nested) != null ? 1 : 0;
                if (count > 0) {
                    counts.put(nested.simpleName(), count);
                }
                collectOuterThisCount(nested, counts);
            }
        }
    }

    /** 查找嵌套类的 this$X 合成字段名,无则返回 null. */
    private String findThisField(TypeDeclaration td) {
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && fd.name().startsWith("this$")) {
                return fd.name();
            }
        }
        // 内部类不引用外层时,合成 this$X 字段被 SourceCleanup 当未使用删掉,
        // 但构造器仍带 this$X 合成参数——据此识别并清理(参数与实例化处去参
        // 都依赖此判定).
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && td.simpleName().equals(md.name())
                    && md.parameterNames().length > 0
                    && md.parameterNames()[0].startsWith("this$")) {
                return md.parameterNames()[0];
            }
        }
        return null;
    }

    /** 递归处理类型:先清理嵌套类型自身并重写其外围类的实例化处,再清理自身. */
    private TypeDeclaration rewriteType(TypeDeclaration td, Map<String, Integer> counts) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof TypeDeclaration nested) {
                members.add(rewriteType(nested, counts));
            } else if (m instanceof MethodDeclaration md && md.body() != null) {
                members.add(withBody(md, new OuterThisArgStripper(counts)
                        .transformMethodBody(md.body())));
            } else if (m instanceof FieldDeclaration fd && fd.initializer() != null) {
                members.add(withInitializer(fd, new OuterThisArgStripper(counts)
                        .transformExpr(fd.initializer())));
            } else {
                members.add(m);
            }
        }
        td = withMembers(td, members);

        String thisField = findThisField(td);
        if (thisField != null) {
            td = cleanOuterThis(td, thisField);
        }
        return td;
    }

    /** 清理嵌套类自身:移除 this$X 字段,清理构造器,重写 this$0.field 引用. */
    private TypeDeclaration cleanOuterThis(TypeDeclaration td, String thisField) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && thisField.equals(fd.name())) {
                continue; // 移除合成字段
            }
            if (m instanceof MethodDeclaration md && md.body() != null) {
                if (td.simpleName().equals(md.name())) {
                    members.add(cleanConstructor(md, thisField));
                } else {
                    members.add(withBody(md, new OuterThisRefRewriter(thisField)
                            .transformMethodBody(md.body())));
                }
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 清理构造器:去掉 this$X 参数与 this.this$X = this$X 赋值. */
    private MethodDeclaration cleanConstructor(MethodDeclaration md, String thisField) {
        String[] names = md.parameterNames();
        JavaType[] types = md.parameterTypes();
        String[] paramAnns = md.parameterAnnotations();
        // 外围 this 是首个合成参数
        int syntheticCount = Math.min(1, names.length);
        String[] newNames = Arrays.copyOfRange(names, syntheticCount, names.length);
        JavaType[] newTypes = Arrays.copyOfRange(types, syntheticCount, types.length);
        String[] newAnns = paramAnns != null
                ? Arrays.copyOfRange(paramAnns, syntheticCount, paramAnns.length) : null;

        Statement body = md.body();
        if (body instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>();
            for (Statement s : bs.statements()) {
                if (isThisFieldAssignment(s, thisField)) {
                    continue;
                }
                stmts.add(s);
            }
            body = new BlockStatement(stmts);
        }
        return withParamsAndBody(md, newNames, newTypes, newAnns, body);
    }

    /** 是否为 this.this$X = this$X 合成赋值语句. */
    private boolean isThisFieldAssignment(Statement s, String thisField) {
        if (!(s instanceof ExpressionStatement es)
                || !(es.expression() instanceof AssignExpr a)
                || !(a.target() instanceof FieldAccessExpr fa)) {
            return false;
        }
        return thisField.equals(fa.fieldName());
    }

    /** 方法体内 this$0.field → field 的直接字段访问重写器. */
    private static final class OuterThisRefRewriter extends AstTransformer {

        private final String thisField;

        OuterThisRefRewriter(String thisField) {
            this.thisField = thisField;
        }

        @Override
        protected Expression transformFieldAccess(FieldAccessExpr e) {
            if (e.target() instanceof VarExpr tv && thisField.equals(tv.name())) {
                // 外围字段直接访问:this$0.secret → secret(隐式 Outer.this.secret)
                return new FieldAccessExpr(null, e.fieldName());
            }
            return super.transformFieldAccess(e);
        }
    }

    /** 外层类实例化处 new Inner(this, ...) → new Inner(...) 去外围 this 参数. */
    private static final class OuterThisArgStripper extends AstTransformer {

        private final Map<String, Integer> counts;

        OuterThisArgStripper(Map<String, Integer> counts) {
            this.counts = counts;
        }

        @Override
        protected Expression transformNew(NewExpr e) {
            if (!e.anonymousBody().isEmpty() || e.instantiatedType() == null
                    || e.instantiatedType().internalName() == null) {
                return super.transformNew(e);
            }
            Integer count = matchCount(e.instantiatedType().internalName());
            if (count != null && count > 0 && e.constructorArgs().size() >= count) {
                List<Expression> args = new ArrayList<>(e.constructorArgs().subList(
                        count, e.constructorArgs().size()));
                return new NewExpr(e.instantiatedType(), e.dimensions(),
                        transformExprList(args), e.anonymousBody(),
                        e.arrayInitializer(), e.typeAnnotations());
            }
            return super.transformNew(e);
        }

        private Integer matchCount(String internal) {
            for (Map.Entry<String, Integer> en : counts.entrySet()) {
                String key = en.getKey();
                if (internal.equals(key)) {
                    return en.getValue();
                }
                // 支持 member(Outer$Inner)与 local(Outer$1LocalClass)命名:
                // 取最后 $ 段并去掉局部类的数字前缀(如 "1LocalClass" → "LocalClass").
                int dollar = internal.lastIndexOf('$');
                if (dollar >= 0) {
                    String last = internal.substring(dollar + 1);
                    int i = 0;
                    while (i < last.length() && Character.isDigit(last.charAt(i))) {
                        i++;
                    }
                    if (last.substring(i).equals(key)) {
                        return en.getValue();
                    }
                }
            }
            return null;
        }
    }
}
