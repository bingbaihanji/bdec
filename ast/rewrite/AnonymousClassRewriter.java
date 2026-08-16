package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.util.ClassNames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 匿名类内联重写器——将反编译为独立嵌套类的匿名类(如 {@code X$1})
 * 内联回实例化处,还原 {@code new InterfaceA() { ... }} 形式,并消除
 * {@code this$0} / {@code val$X} 合成字段与捕获痕迹.
 *
 * <p>参考 CFR 的 {@code CodeAnalyserWholeClass} 与 Vineflower 的
 * {@code NestedClassProcessor} 设计.匿名类字节码形如:</p>
 * <pre>
 *   class X$1 implements Runnable {
 *       final X this$0;                 // 合成外围引用(可能仅作为构造参数存在)
 *       final int val$local;            // 合成捕获局部变量
 *       X$1(X this$0, int val$local) {  // 合成构造参数
 *           this.this$0 = this$0; this.val$local = val$local; super();
 *       }
 *       public void run() { ... this$0.field + val$local ... }
 *   }
 *   // 使用点:return new X$1(this, 10);
 * </pre>
 *
 * <p>内联后还原为 {@code new Runnable() { public void run() { ... field + local ... } }},
 * 其中外围字段访问 {@code this$0.field} → {@code field},捕获字段 {@code val$local}
 * → 原局部变量 {@code local}(剥离 {@code val$} 前缀),合成构造参数从 {@code new}
 * 实参中剥离.</p>
 *
 * <p><b>捕获局部变量重建</b>:捕获值在 SSA 复制传播中可能被常量内联导致外层
 * 局部变量声明被死代码消除(如 {@code int local = 10} 被折叠为常量实参),
 * 匿名类体中的 {@code local} 引用即失去声明.本重写器在实例化语句前重建
 * {@code int local = 10;} 声明,使输出可重新编译.</p>
 */
public class AnonymousClassRewriter implements RewriteRule {

    /** 匿名类内联期间的外层类型名(渲染 this$0 → Enclosing.this 用). */
    private String currentEnclosingName = "";

    /** 剥离 {@code val$} 前缀得到原局部变量名({@code val$local} → {@code local}). */
    private static String stripVal(String name) {
        return name.startsWith("val$") ? name.substring(4) : name;
    }

    /** 剥离类型引用中的泛型参数(如 "List<String>" → "List"),供父类型解析用. */
    private static String stripGenericArgs(String ref) {
        int lt = ref.indexOf('<');
        return lt >= 0 ? ref.substring(0, lt) : ref;
    }

    /**
     * 解析匿名类父类引用(可能携带泛型实参,如 {@code MapCollections<K, V>}).
     * 匿名类继承泛型父类时方法体使用继承的类型变量(如 colPut(K key)),raw
     * 实例化 {@code new MapCollections()} 使类型变量无法解析——须保留实参
     * 渲染为 {@code new MapCollections<K, V>()}.
     */
    private static JavaType parseBaseRef(String ref) {
        int lt = ref.indexOf('<');
        if (lt < 0) {
            return JavaType.classType(ref);
        }
        String base = ref.substring(0, lt).trim();
        String argsStr = ref.substring(lt + 1, Math.max(lt + 1, ref.lastIndexOf('>')));
        List<JavaType> typeArgs = new ArrayList<>();
        for (String tok : argsStr.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) {
                continue;
            }
            // 简单类型变量名(单大写字母,如 K/V)→ TYPE_VARIABLE;否则按类名(粗粒度)
            if (Character.isUpperCase(t.charAt(0)) && t.length() == 1) {
                typeArgs.add(JavaType.typeVariable(t));
            } else {
                typeArgs.add(JavaType.classType(t));
            }
        }
        return new JavaType(com.bingbaihanji.bdec.type.TypeKind.CLASS, base,
                "L" + base + ";", typeArgs, 0);
    }

    @Override
    public String name() {return "anonymous-class";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        Map<String, TypeDeclaration> anonTypes = new HashMap<>();
        collectAnon(unit.types(), anonTypes);
        if (anonTypes.isEmpty()) {
            return unit;
        }

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            if (anonTypes.containsKey(td.simpleName())) {
                continue; // 顶层匿名类:内联后无需独立声明
            }
            types.add(rewriteType(td, anonTypes));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types,
                unit.innerClassNames());
    }

    // ── 语句级内联(支持在实例化语句前重建捕获局部变量声明) ──

    /** 收集所有匿名类 TypeDeclaration(顶层或嵌套),按简单名索引. */
    private void collectAnon(List<TypeDeclaration> types, Map<String, TypeDeclaration> anonTypes) {
        for (TypeDeclaration td : types) {
            if (ClassNames.isAnonymousClassName(td.simpleName())) {
                anonTypes.put(td.simpleName(), td);
            }
            for (AstNode m : td.children()) {
                if (m instanceof TypeDeclaration nested) {
                    collectAnon(List.of(nested), anonTypes);
                }
            }
        }
    }

    /** 递归处理类型:移除匿名类声明,并在方法体/字段初始化器中内联实例化. */
    private TypeDeclaration rewriteType(TypeDeclaration td,
                                        Map<String, TypeDeclaration> anonTypes) {
        // 匿名类内联期间的外层类型名:this$0(外层引用)渲染为 Enclosing.this
        currentEnclosingName = td.simpleName();
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof TypeDeclaration nested) {
                if (anonTypes.containsKey(nested.simpleName())) {
                    continue; // 移除独立匿名类声明(已内联到实例化处)
                }
                members.add(rewriteType(nested, anonTypes));
            } else if (m instanceof MethodDeclaration md && md.body() != null) {
                members.add(withBody(md, rewriteMethodBody(md.body(), anonTypes)));
            } else if (m instanceof FieldDeclaration fd && fd.initializer() != null) {
                members.add(withInitializer(fd, inlineExpr(fd.initializer(), anonTypes)));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 字段初始化器内联(字段级无捕获局部变量,重建声明列表为空,可丢弃). */
    private Expression inlineExpr(Expression e, Map<String, TypeDeclaration> anonTypes) {
        InlineTransformer tr = new InlineTransformer(anonTypes);
        return tr.transformExpr(e);
    }

    private Statement rewriteMethodBody(Statement body, Map<String, TypeDeclaration> anonTypes) {
        if (body instanceof BlockStatement bs) {
            return new BlockStatement(rewriteStatements(bs.statements(), anonTypes));
        }
        List<Statement> stmts = rewriteStatement(body, anonTypes);
        return stmts.size() == 1 ? stmts.getFirst() : new BlockStatement(stmts);
    }

    // ── 表达式级内联 ──

    private List<Statement> rewriteStatements(List<Statement> stmts,
                                              Map<String, TypeDeclaration> anonTypes) {
        List<Statement> out = new ArrayList<>();
        for (Statement s : stmts) {
            out.addAll(rewriteStatement(s, anonTypes));
        }
        return out;
    }

    /**
     * 递归重写一条语句,返回重写后的一条或多条语句(多条时首部为重建的
     * 捕获局部变量声明).容器语句(if/loop/try/switch/synchronized)递归处理
     * 其子语句;叶语句(return/throw/表达式/变量声明)内联其中出现的匿名类实例化.
     */
    private List<Statement> rewriteStatement(Statement s,
                                             Map<String, TypeDeclaration> anonTypes) {
        if (s instanceof BlockStatement bs) {
            return List.of(new BlockStatement(rewriteStatements(bs.statements(), anonTypes)));
        }
        if (s instanceof IfStatement i) {
            Statement thenB = asSingle(rewriteStatement(i.thenBranch(), anonTypes));
            Statement elseB = i.elseBranch() != null
                    ? asSingle(rewriteStatement(i.elseBranch(), anonTypes)) : null;
            return List.of(new IfStatement(i.condition(), thenB, elseB));
        }
        if (s instanceof LoopStatement l) {
            return List.of(withLoopBody(l, asSingle(rewriteStatement(l.body(), anonTypes))));
        }
        if (s instanceof TryStatement t) {
            Statement tryBody = asSingle(rewriteStatement(t.tryBody(), anonTypes));
            List<TryStatement.CatchClause> catches = new ArrayList<>();
            for (TryStatement.CatchClause cc : t.catchClauses()) {
                catches.add(new TryStatement.CatchClause(cc.exceptionType(), cc.varName(),
                        asSingle(rewriteStatement(cc.body(), anonTypes))));
            }
            Statement finBody = t.finallyBody() != null
                    ? asSingle(rewriteStatement(t.finallyBody(), anonTypes)) : null;
            return List.of(new TryStatement(tryBody, catches, finBody, t.resources()));
        }
        if (s instanceof SwitchStatement sw) {
            List<SwitchStatement.CaseGroup> cases = new ArrayList<>();
            for (SwitchStatement.CaseGroup cg : sw.cases()) {
                cases.add(new SwitchStatement.CaseGroup(cg.labels(),
                        rewriteStatements(cg.body(), anonTypes), cg.isDefault()));
            }
            return List.of(new SwitchStatement(sw.discriminant(), cases, sw.isExpression()));
        }
        if (s instanceof SynchronizedStatement sync) {
            return List.of(new SynchronizedStatement(sync.monitorObject(),
                    asSingle(rewriteStatement(sync.body(), anonTypes))));
        }
        // 叶语句:内联表达式并前置重建的捕获局部变量声明
        InlineTransformer tr = new InlineTransformer(anonTypes);
        Statement t = tr.transformStmt(s);
        List<Statement> decls = tr.drainDecls();
        if (decls.isEmpty()) {
            return List.of(t);
        }
        List<Statement> out = new ArrayList<>(decls);
        out.add(t);
        return out;
    }

    /** 单条语句直接返回,多条时包成块(容器子语句重建捕获局部变量时). */
    private Statement asSingle(List<Statement> stmts) {
        return stmts.size() == 1 ? stmts.getFirst() : new BlockStatement(stmts);
    }

    /** 匿名类构造器是否含外围 this 合成参数(首个参数名为 this$X). */
    private boolean hasThisParam(TypeDeclaration anon) {
        for (AstNode m : anon.children()) {
            if (m instanceof MethodDeclaration md && anon.simpleName().equals(md.name())) {
                String[] names = md.parameterNames();
                if (names != null && names.length > 0 && names[0] != null
                        && names[0].startsWith("this$")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 匿名类的捕获局部变量字段(val$X),按声明顺序. */
    private List<FieldDeclaration> valFields(TypeDeclaration anon) {
        List<FieldDeclaration> fields = new ArrayList<>();
        for (AstNode m : anon.children()) {
            if (m instanceof FieldDeclaration fd && fd.name().startsWith("val$")) {
                fields.add(fd);
            }
        }
        return fields;
    }

    /** 匿名类的父类型(接口或父类). */
    private JavaType anonBaseType(TypeDeclaration anon) {
        if (!anon.interfaceNames().isEmpty()) {
            return parseBaseRef(anon.interfaceNames().getFirst());
        }
        if (anon.superName() != null && !anon.superName().isEmpty()
                && !"java/lang/Object".equals(anon.superName())) {
            return parseBaseRef(anon.superName());
        }
        return JavaType.classType("java/lang/Object");
    }

    /** JavaType 的简单名(内部名最后一段). */
    private String simpleTypeName(JavaType t) {
        if (t == null || t.internalName() == null) {
            return "";
        }
        String internal = t.internalName();
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    /** 类体内 this$0.field → field,this$0.method() → method(),val$x → x,
     *  裸 this$0/(T) this$0 → Enclosing.this 的引用重写器. */
    private static final class OuterRefRewriter extends AstTransformer {

        private final String enclosingName;

        OuterRefRewriter(String enclosingName) {
            this.enclosingName = enclosingName;
        }

        @Override
        protected Expression transformVarExpr(VarExpr e) {
            if (e.name().startsWith("val$")) {
                return new VarExpr(stripVal(e.name()));
            }
            // 裸 this$0(作为值传递/返回)→ 外层实例引用
            if (e.name().startsWith("this$") && !enclosingName.isEmpty()) {
                return new VarExpr(enclosingName + ".this");
            }
            return e;
        }

        @Override
        protected Expression transformFieldAccess(FieldAccessExpr e) {
            // this$0.field → field(隐式外围字段访问)
            if (e.target() instanceof VarExpr tv && tv.name().startsWith("this$")) {
                return new FieldAccessExpr(null, e.fieldName());
            }
            // val$x(显式 this.val$x 或隐式) → x(原局部变量)
            if (e.fieldName() != null && e.fieldName().startsWith("val$")) {
                return new VarExpr(stripVal(e.fieldName()));
            }
            return super.transformFieldAccess(e);
        }

        @Override
        protected Expression transformInvocation(InvocationExpr e) {
            // this$0.method(...) → method(...)(隐式外围方法调用)
            if (e.target() instanceof VarExpr tv && tv.name().startsWith("this$")) {
                return new InvocationExpr(null, e.methodName(), e.arguments(),
                        e.returnType());
            }
            return super.transformInvocation(e);
        }

        @Override
        protected Expression transformCast(CastExpr e) {
            // (T) this$0 → 外层实例引用(非静态匿名类 this$0 即外层 ArrayMap)
            if (e.operand() instanceof VarExpr tv && tv.name().startsWith("this$")
                    && !enclosingName.isEmpty()) {
                return new VarExpr(enclosingName + ".this");
            }
            return super.transformCast(e);
        }
    }

    /** 在表达式中把 {@code new X$N(...)} 内联为 {@code new 父类型(...) { 体 }},
     *  并把需重建的捕获局部变量声明累积到 {@code pendingDecls}. */
    private final class InlineTransformer extends AstTransformer {

        private final Map<String, TypeDeclaration> anonTypes;

        private final List<Statement> pendingDecls = new ArrayList<>();

        InlineTransformer(Map<String, TypeDeclaration> anonTypes) {
            this.anonTypes = anonTypes;
        }

        /** 取出并清空累积的重建声明. */
        List<Statement> drainDecls() {
            if (pendingDecls.isEmpty()) {
                return List.of();
            }
            List<Statement> r = new ArrayList<>(pendingDecls);
            pendingDecls.clear();
            return r;
        }

        @Override
        protected Expression transformNew(NewExpr e) {
            if (!e.anonymousBody().isEmpty() || e.instantiatedType() == null) {
                return super.transformNew(e);
            }
            TypeDeclaration anon = anonTypes.get(simpleTypeName(e.instantiatedType()));
            if (anon == null) {
                return super.transformNew(e);
            }
            return buildAnonymousNew(e, anon);
        }

        @Override
        protected Statement transformVarDecl(VariableDeclaration s) {
            if (s.initializer() == null) {
                return s;
            }
            // 匿名类实例化的变量声明类型应改为父类型(VarAnon$1 → Runnable)
            JavaType newType = s.type();
            if (s.initializer() instanceof NewExpr ne) {
                TypeDeclaration anon = anonTypes.get(simpleTypeName(ne.instantiatedType()));
                if (anon != null) {
                    newType = anonBaseType(anon);
                }
            }
            Expression init = transformExpr(s.initializer());
            return (init != s.initializer() || newType != s.type())
                    ? new VariableDeclaration(newType, s.name(), init, s.typeAnnotations()) : s;
        }

        /** 构建匿名类实例化:父类型 + 去合成参数的实参 + 清理合成字段后的类体. */
        private NewExpr buildAnonymousNew(NewExpr ne, TypeDeclaration anon) {
            // 合成构造参数(外围 this 在前,捕获局部变量随后)按构造器参数名计数,
            // 而非按字段计数——外围 this 参数即使未被使用(无对应字段)仍会作为
            // 首个实参传入,仅按字段计数会漏剥.
            boolean hasThis = hasThisParam(anon);
            List<FieldDeclaration> valFields = valFields(anon);
            int syntheticCount = (hasThis ? 1 : 0) + valFields.size();

            List<Expression> rawArgs = ne.constructorArgs();
            // 捕获局部变量值经复制传播可能已折叠为常量,外层声明被死代码消除,
            // 此处重建声明;值若仍是原局部变量引用(VarExpr)则外层声明仍在,无需重建.
            for (int i = 0; i < valFields.size(); i++) {
                int argIdx = (hasThis ? 1 : 0) + i;
                if (argIdx >= rawArgs.size()) {
                    break;
                }
                Expression value = rawArgs.get(argIdx);
                String localName = stripVal(valFields.get(i).name());
                if (!(value instanceof VarExpr v && localName.equals(v.name()))) {
                    pendingDecls.add(new VariableDeclaration(
                            valFields.get(i).type(), localName, value,
                            valFields.get(i).typeAnnotations()));
                }
            }

            // 剥离合成构造实参(外围 this + 捕获局部变量)
            List<Expression> args = new ArrayList<>(rawArgs.subList(
                    Math.min(syntheticCount, rawArgs.size()), rawArgs.size()));

            // 类体:方法与字段声明(排除构造器与合成字段),并重写外围/捕获引用
            OuterRefRewriter ref = new OuterRefRewriter(currentEnclosingName);
            List<AstNode> body = new ArrayList<>();
            for (AstNode m : anon.children()) {
                if (m instanceof MethodDeclaration md && md.name() != null
                        && !md.name().equals(anon.simpleName())) {
                    Statement newBody = md.body() != null
                            ? ref.transformMethodBody(md.body()) : null;
                    body.add(withBody(md, newBody));
                } else if (m instanceof FieldDeclaration fd
                        && !fd.name().startsWith("this$")
                        && !fd.name().startsWith("val$")) {
                    body.add(fd);
                }
            }
            return new NewExpr(anonBaseType(anon), List.of(), args, body, List.of(),
                    ne.typeAnnotations());
        }
    }
}
