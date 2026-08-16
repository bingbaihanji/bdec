package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.PatternLabel;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SwitchStatement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * 语句发射器,将 AST 语句节点输出为 Java 源代码文本.
 * 实现 AstVisitor 接口以支持访问者模式分发.
 * 支持块语句,控制流语句(if/for/while/switch/try 等),
 * 方法声明,字段声明,变量声明等所有 Java 语句类型的发射.
 */
public class StatementEmitter implements AstVisitor<Void, Void> {

    /** 缩进写入器,用于格式化输出源代码 */
    private final IndentWriter w;

    /** 表达式发射器,用于输出语句中的表达式部分 */
    private final ExpressionEmitter exprs;

    /** 当前类型的简单类名,用于判断方法是否为构造器 */
    private final String className;

    /** 当前封闭类型是否为接口(影响 default 方法的输出) */
    private final boolean isInterface;

    /** 当前封闭类型是否为注解类型(影响元素方法的输出) */
    private final boolean isAnnotationType;

    /**
     * 构造语句发射器.
     *
     * @param w           缩进写入器
     * @param exprs       表达式发射器
     * @param className   类名(用于识别构造器)
     * @param isInterface 是否为接口类型
     */
    public StatementEmitter(IndentWriter w, ExpressionEmitter exprs, String className,
                            boolean isInterface, boolean isAnnotationType) {
        this.w = w;
        this.exprs = exprs;
        this.className = className;
        this.isInterface = isInterface;
        this.isAnnotationType = isAnnotationType;
    }

    /** 兼容构造器:接口/注解标志由 kindName 判定 */
    public StatementEmitter(IndentWriter w, ExpressionEmitter exprs, String className,
                            boolean isInterface) {
        this(w, exprs, className, isInterface, false);
    }

    /**
     * 兼容旧调用的构造器,默认非接口类型.
     *
     * @param w         缩进写入器
     * @param exprs     表达式发射器
     * @param className 类名
     */
    public StatementEmitter(IndentWriter w, ExpressionEmitter exprs, String className) {
        this(w, exprs, className, false);
    }

    /** @return 当前类的简单名称(用于构造函数检测) */
    public String className() {return className;}

    /** @return 表达式发射器 */
    public ExpressionEmitter exprs() {return exprs;}

    /**
     * 委托给 ExpressionEmitter 解析类型名称为最短有效形式.
     *
     * @param t Java 类型
     * @return 类型名称字符串
     */
    private String typeName(com.bingbaihanji.bdec.type.JavaType t) {
        return exprs.typeName(t);
    }

    /** 渲染带 JSR-308 类型注解的类型名(注解按类型路径定位) */
    private String typeNameAnnotated(
            com.bingbaihanji.bdec.type.JavaType t,
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    java.util.List<String>> anns) {
        return exprs.typeNameAnnotated(t, anns);
    }

    @Override
    public Void visitStatement(Statement stmt, Void context) {
        emit(stmt);
        return null;
    }

    @Override
    public Void visitExpression(Expression expr, Void context) {
        exprs.emit(expr);
        return null;
    }

    /**
     * 根据语句类型分发到具体的发射方法.
     *
     * @param stmt 要发射的语句节点
     */
    public void emit(Statement stmt) {
        if (stmt == null) {
            System.err.println("WARNING: StatementEmitter.emit() called with null statement, skipping");
            new Exception("null stmt trace").printStackTrace(System.err);
            return;
        }
        switch (stmt.kind()) {
            case BLOCK -> emitBlock((BlockStatement) stmt);
            case IF -> emitIf((IfStatement) stmt);
            case LOOP -> emitLoop((LoopStatement) stmt);
            case RETURN -> emitReturn((ReturnStatement) stmt);
            case METHOD_DECL -> emitMethodDecl((MethodDeclaration) stmt);
            case EXPRESSION_STMT -> emitExprStmt((ExpressionStatement) stmt);
            case FIELD_DECL -> emitFieldDecl((FieldDeclaration) stmt);
            case THROW -> emitThrow(stmt);
            case SWITCH -> emitSwitch(stmt);
            case VARIABLE_DECL -> emitVariableDecl(stmt);
            case BREAK -> w.token("break").write(";").newLine();
            case CONTINUE -> w.token("continue").write(";").newLine();
            case GOTO -> emitGoto((com.bingbaihanji.bdec.ast.stmt.GotoStatement) stmt);
            case LABEL -> emitLabel((com.bingbaihanji.bdec.ast.stmt.LabelStatement) stmt);
            case SYNCHRONIZED -> emitSynchronized(stmt);
            case TRY -> emitTry(stmt);
            default -> w.write("// " + stmt.kind()).newLine();
        }
    }

    /**
     * 发射代码块语句,包裹在花括号内并处理缩进.
     *
     * @param b 块语句节点
     */
    private void emitBlock(BlockStatement b) {
        w.write("{").newLine();
        w.indent();
        List<Statement> stmts = b.statements();
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s == null) {
                System.err.println("WARNING: null statement at index " + i + " in BlockStatement, skipping");
                continue;
            }
            // 跳过 void 方法或构造器末尾多余的 "return;"
            //(JVM 要求在方法末尾有 return 指令,但 Java 源码中无需显示写出)
            if (isTrailingVoidReturn(s, stmts, i)) {
                continue;
            }
            emit(s);
        }
        w.dedent();
        w.write("}").newLine();
    }

    /** 检查是否为块中无意义的末尾 void return */
    private boolean isTrailingVoidReturn(Statement s, List<Statement> stmts, int idx) {
        if (!(s instanceof ReturnStatement rs)) {
            return false;
        }
        if (rs.value() != null) {
            return false; // 有返回值,需要保留
        }
        // 仅当是最后一条语句(或之后只有 null/空语句)时才跳过
        for (int j = idx + 1; j < stmts.size(); j++) {
            Statement next = stmts.get(j);
            if (next != null && !isEmptyNoOp(next)) {
                return false;
            }
        }
        return true;
    }

    /** 检查语句是否为空操作(无意义的空块或null) */
    private boolean isEmptyNoOp(Statement s) {
        if (s == null) {
            return true;
        }
        return s instanceof BlockStatement bs && bs.statements().isEmpty();
    }

    /**
     * 发射 if/if-else 条件语句.
     *
     * @param i if 语句节点
     */
    private void emitIf(IfStatement i) {
        w.token("if").space().write("(");
        exprs.emit(i.condition());
        w.write(")").space();
        emitBranched(i.thenBranch());
        if (i.elseBranch() != null) {
            w.space().token("else").space();
            emitBranched(i.elseBranch());
        }
    }

    /**
     * 发射循环语句(for/while/do-while/for-each).
     *
     * @param l 循环语句节点
     */
    private void emitLoop(LoopStatement l) {
        switch (l.loopKind()) {
            case DO_WHILE -> {
                w.token("do").space();
                emitBranched(l.body());
                w.space().token("while").space().write("(");
                if (l.condition() != null) {
                    exprs.emit(l.condition());
                }
                w.write(");").newLine();
            }
            case FOR_EACH -> {
                w.token("for").space().write("(");
                // 输出变量和可迭代对象:"Type var : iterable"
                if (l.forEachVar() != null) {
                    // 若为裸 VarExpr(无类型信息),补全类型声明
                    if (l.forEachVar() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                            && !(l.forEachVar() instanceof com.bingbaihanji.bdec.ast.expr.AssignExpr)) {
                        if (l.forEachVarType() != null) {
                            w.write(typeName(l.forEachVarType())).space().write(v.name());
                        } else {
                            w.write("Object ").write(v.name());
                        }
                    } else {
                        exprs.emit(l.forEachVar());
                    }
                } else if (l.initExpr() != null) {
                    exprs.emit(l.initExpr());
                } else {
                    w.write("Object element");
                }
                w.space().write(":").space();
                if (l.condition() != null) {
                    exprs.emit(l.condition());
                }
                w.write(")").space();
                emitBranched(l.body());
            }
            case FOR -> {
                w.token("for").space().write("(");
                // 输出初始化表达式
                if (l.initExpr() != null) {
                    exprs.emit(l.initExpr());
                }
                w.write("; ");
                // 输出条件表达式
                if (l.condition() != null) {
                    exprs.emit(l.condition());
                }
                w.write("; ");
                // 输出递增表达式
                if (l.incrExpr() != null) {
                    exprs.emit(l.incrExpr());
                }
                w.write(")").space();
                emitBranched(l.body());
            }
            case WHILE -> {
                w.token("while").space().write("(");
                if (l.condition() != null) {
                    exprs.emit(l.condition());
                }
                w.write(")").space();
                emitBranched(l.body());
            }
        }
    }

    /**
     * 发射 return 语句.
     *
     * @param r return 语句节点
     */
    private void emitReturn(ReturnStatement r) {
        w.token("return");
        if (r.value() != null) {
            w.space();
            exprs.emit(r.value());
        }
        w.write(";").newLine();
    }

    /**
     * 发射方法声明,包括修饰符,泛型参数,返回值类型,参数列表和方法体.
     * 同时处理构造函数中隐式 super() 调用的过滤.
     *
     * @param m 方法声明节点
     */
    private void emitMethodDecl(MethodDeclaration m) {
        // 方法上的注解(如 @Override,@AnnotationDemo(value = "testMethod", count = 5))
        for (String ann : m.annotations()) {
            w.write(ann).newLine();
        }
        // 注解元素方法:@interface 隐式 public abstract,不输出修饰符
        if (isAnnotationType) {
            w.write(typeNameAnnotated(m.returnType(), m.typeAnnotations().onType()))
                    .space().write(m.name()).write("()");
            if (m.annotationDefault() != null) {
                w.space().token("default").space().write(m.annotationDefault());
            }
            w.write(";").newLine();
            return;
        }
        // 输出方法修饰符
        ModifierRenderer.emitMethodModifiers(m.accessFlags(), isInterface, w);

        // 输出方法级别的泛型类型参数:<T>
        if (!m.typeParameters().isEmpty()) {
            w.write("<");
            w.write(String.join(", ", m.typeParameters()));
            w.write(">").space();
        }

        String methodName = m.name();
        if (methodName == null || methodName.isEmpty()) {
            // 静态初始化块 — 仅输出 "static { }"
            w.write("{").newLine();
            w.indent();
            if (m.body() != null) {
                if (m.body() instanceof BlockStatement bs) {
                    for (Statement s : bs.statements()) {
                        emit(s);
                    }
                } else {
                    emit(m.body());
                }
            }
            w.dedent();
            w.write("}").newLine();
            return;
        }

        // record 紧凑构造器:无参数列表,输出 "RecordName { ... }"
        if (m.compactConstructor()) {
            w.write(methodName).space();
            if (m.body() != null) {
                emit(m.body());
            } else {
                w.write("{}").newLine();
            }
            return;
        }

        // 判断是否为构造函数(方法名与类名相同)
        boolean isConstructor = methodName.equals(className);
        if (isConstructor) {
            w.write(methodName).write("(");
        } else {
            w.write(typeNameAnnotated(m.returnType(), m.typeAnnotations().onType()))
                    .space().write(methodName).write("(");
        }

        // 输出参数列表(参数级注解内联在类型之前:void m(@Ann("x") String s))
        String[] pAnns = m.parameterAnnotations();
        var paramTypeAnns = m.typeAnnotations().onParameters();
        boolean isVarargs = (m.accessFlags() & com.bingbaihanji.bdec.bytecode.model.AccessFlags.ACC_VARARGS) != 0;
        for (int i = 0; i < m.parameterNames().length; i++) {
            if (i > 0) {
                w.write(", ");
            }
            if (pAnns != null && i < pAnns.length && pAnns[i] != null) {
                w.write(pAnns[i]).space();
            }
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    java.util.List<String>> paramAnns =
                    i < paramTypeAnns.size() ? paramTypeAnns.get(i) : java.util.Map.of();
            com.bingbaihanji.bdec.type.JavaType pt = m.parameterTypes()[i];
            // varargs:最后一个数组参数渲染为 "T... name"(ACC_VARARGS 标志),
            // 否则 "@SafeVarargs 方法非 varargs" 编译错误.
            if (isVarargs && i == m.parameterNames().length - 1
                    && pt.kind() == com.bingbaihanji.bdec.type.TypeKind.ARRAY) {
                w.write(typeNameAnnotated(
                                com.bingbaihanji.bdec.type.JavaType.elementOf(pt), paramAnns))
                        .write("...").space().write(m.parameterNames()[i]);
            } else {
                w.write(typeNameAnnotated(pt, paramAnns))
                        .space().write(m.parameterNames()[i]);
            }
        }
        w.write(")");

        // 输出 throws 子句(构造函数与普通方法均可声明异常);
        // throws 类型注解(0x17)位于异常类型根上,路径为空
        if (!m.throwsTypes().isEmpty()) {
            w.space().token("throws").space();
            var throwsTypeAnns = m.typeAnnotations().onThrows();
            for (int i = 0; i < m.throwsTypes().size(); i++) {
                if (i > 0) {
                    w.write(", ");
                }
                if (i < throwsTypeAnns.size()) {
                    for (String a : throwsTypeAnns.get(i)
                            .getOrDefault(java.util.List.of(), java.util.List.of())) {
                        w.write(a).space();
                    }
                }
                w.write(m.throwsTypes().get(i));
            }
        }
        w.space();

        // 输出方法体
        if (m.body() != null) {
            // 过滤构造函数中的隐式 super():若第一个语句为无参 super() 调用则跳过
            Statement body = m.body();
            if (isConstructor && body instanceof BlockStatement bs
                    && !bs.statements().isEmpty()) {
                Statement first = bs.statements().getFirst();
                if (isImplicitSuperCall(first)) {
                    List<Statement> filtered = new ArrayList<>(
                            bs.statements().subList(1, bs.statements().size()));
                    body = new BlockStatement(filtered);
                }
            }
            emit(body);
        } else {
            w.write(";").newLine();
        }
    }

    /**
     * 检查语句是否为隐式的无参 super() 构造函数调用.
     *
     * @param s 待检查的语句
     * @return 是隐式 super() 调用返回 true
     */
    private boolean isImplicitSuperCall(Statement s) {
        if (s instanceof ExpressionStatement es
                && es.expression() instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            return "super".equals(inv.methodName()) && inv.arguments().isEmpty();
        }
        return false;
    }

    /**
     * 发射表达式语句(表达式后跟分号).
     *
     * @param e 表达式语句节点
     */
    private void emitExprStmt(ExpressionStatement e) {
        exprs.emit(e.expression());
        w.write(";").newLine();
    }

    /**
     * 发射字段声明,包括修饰符,类型,字段名和初始化表达式.
     *
     * @param f 字段声明节点
     */
    private void emitFieldDecl(FieldDeclaration f) {
        // 字段上的注解(如 @AnnotationDemo(value = "field", count = 1))
        for (String ann : f.annotations()) {
            w.write(ann).newLine();
        }
        ModifierRenderer.emitFieldModifiers(f.accessFlags(), w);
        w.write(typeNameAnnotated(f.type(), f.typeAnnotations())).space().write(f.name());
        if (f.initializer() != null) {
            w.space().write("=").space();
            exprs.emit(f.initializer());
        }
        w.write(";").newLine();
    }

    /**
     * 发射 try-catch-finally 语句.try 必须至少包含一个 catch 或 finally 块.
     *
     * @param stmt try 语句节点
     */
    private void emitTry(Statement stmt) {
        if (stmt instanceof TryStatement tryStmt) {
            boolean hasCatch = !tryStmt.catchClauses().isEmpty();
            boolean hasFinally = tryStmt.finallyBody() != null;
            boolean hasResources = !tryStmt.resources().isEmpty();
            // 安全检查:try 必须至少有一个 catch / finally / 资源
            if (!hasCatch && !hasFinally && !hasResources) {
                w.write("/* empty try */ { }").newLine();
                return;
            }
            w.token("try").space();
            if (hasResources) {
                w.write("(");
                List<TryStatement.Resource> resources = tryStmt.resources();
                for (int i = 0; i < resources.size(); i++) {
                    if (i > 0) {
                        w.write(";").space();
                    }
                    TryStatement.Resource r = resources.get(i);
                    w.write(typeNameAnnotated(r.type(), java.util.Map.of())).space()
                            .write(r.varName()).space().write("=").space();
                    exprs.emit(r.init());
                }
                w.write(")").space();
            }
            emitBranched(tryStmt.tryBody());
            for (TryStatement.CatchClause cc : tryStmt.catchClauses()) {
                w.space().token("catch").space().write("(")
                        .write(cc.exceptionType()).space().write(cc.varName()).write(")").space();
                emitBranched(cc.body());
            }
            if (hasFinally) {
                w.space().token("finally").space();
                emitBranched(tryStmt.finallyBody());
            }
        } else {
            w.write("/* try */").newLine();
        }
    }

    /**
     * 发射 synchronized 同步块语句.
     *
     * @param stmt synchronized 语句节点
     */
    private void emitSynchronized(Statement stmt) {
        if (stmt instanceof SynchronizedStatement sync) {
            w.token("synchronized").space().write("(");
            exprs.emit(sync.monitorObject());
            w.write(")").space();
            emitBranched(sync.body());
        } else {
            w.write("synchronized (obj) {}").newLine();
        }
    }

    /**
     * 发射局部变量声明语句.
     *
     * @param stmt 变量声明语句节点
     */
    private void emitVariableDecl(Statement stmt) {
        if (stmt instanceof VariableDeclaration vd) {
            w.write(typeNameAnnotated(vd.type(), vd.typeAnnotations()))
                    .space().write(vd.name());
            if (vd.initializer() != null) {
                w.space().write("=").space();
                exprs.emit(vd.initializer());
            }
            w.write(";").newLine();
        } else {
            w.write("/* var decl */;").newLine();
        }
    }

    /**
     * 发射 throw 抛出异常语句.
     *
     * @param stmt throw 语句节点
     */
    /** 发射不可归约兜底的 goto 跳转语句. */
    private void emitGoto(com.bingbaihanji.bdec.ast.stmt.GotoStatement g) {
        w.token("goto").space().write(g.label()).write(";").newLine();
    }

    /** 发射不可归约兜底的标签声明(行首,不缩进加码). */
    private void emitLabel(com.bingbaihanji.bdec.ast.stmt.LabelStatement l) {
        w.write(l.label()).write(":").newLine();
    }

    private void emitThrow(Statement stmt) {
        w.token("throw").space();
        if (stmt instanceof ThrowStatement ts && ts.expression() != null) {
            exprs.emit(ts.expression());
        } else if (!stmt.children().isEmpty() && stmt.children().getFirst() instanceof Expression ex) {
            exprs.emit(ex);
        } else {
            w.write("new RuntimeException()");
        }
        w.write(";").newLine();
    }

    /**
     * 发射 switch 语句,支持传统的冒号式 switch 和箭头式 switch 表达式.
     *
     * @param stmt switch 语句节点
     */
    private void emitSwitch(Statement stmt) {
        if (stmt instanceof SwitchStatement sw) {
            w.token("switch").space().write("(");
            exprs.emit(sw.discriminant());
            w.write(")").space().write("{").newLine();
            w.indent();
            String arrow = sw.isExpression() ? " -> " : ":";
            for (SwitchStatement.CaseGroup cg : sw.cases()) {
                if (cg.isDefault()) {
                    w.token("default").write(arrow);
                    if (sw.isExpression() && !cg.body().isEmpty()) {
                        Statement s = simplifyCaseBody(cg.body());
                        if (s instanceof BlockStatement) {
                            w.write("{").newLine();
                            w.indent();
                            for (Statement bs : ((BlockStatement) s).statements()) {
                                emit(bs);
                            }
                            w.dedent();
                            w.write("}").newLine();
                        } else if (s instanceof ExpressionStatement es) {
                            exprs.emit(es.expression());
                            w.write(";").newLine();
                        } else {
                            emit(s);
                        }
                    } else {
                        w.newLine();
                        w.indent();
                        for (Statement s : cg.body()) {
                            emit(s);
                        }
                        w.dedent();
                    }
                } else {
                    for (Expression label : cg.labels()) {
                        w.token("case").space();
                        emitCaseLabel(label);
                        w.write(arrow);
                        if (sw.isExpression() && !cg.body().isEmpty()) {
                            Statement s = simplifyCaseBody(cg.body());
                            if (s instanceof BlockStatement) {
                                w.write("{").newLine();
                                w.indent();
                                for (Statement bs : ((BlockStatement) s).statements()) {
                                    emit(bs);
                                }
                                w.dedent();
                                w.write("}").newLine();
                            } else if (s instanceof ExpressionStatement es) {
                                exprs.emit(es.expression());
                                w.write(";").newLine();
                            } else {
                                emit(s);
                            }
                        } else {
                            w.newLine();
                            w.indent();
                            for (Statement s : cg.body()) {
                                emit(s);
                            }
                            w.dedent();
                        }
                    }
                }
            }
            w.dedent();
            w.write("}").newLine();
        } else {
            // 降级处理:输出通用 switch 占位符
            w.token("switch").space().write("(");
            if (!stmt.children().isEmpty() && stmt.children().getFirst() instanceof Expression ex) {
                exprs.emit(ex);
            } else {
                w.write("/* expr */");
            }
            w.write(")").space().write("{").newLine();
            w.indent();
            w.write("// TODO: full switch emission").newLine();
            w.dedent();
            w.write("}").newLine();
        }
    }

    /**
     * 发射单个 case 标签.模式标签({@link PatternLabel})渲染为
     * {@code null} 或 {@code Type var when guard},普通标签走表达式发射器.
     *
     * @param label case 标签表达式
     */
    private void emitCaseLabel(Expression label) {
        if (label instanceof PatternLabel pl) {
            if (pl.nullCase()) {
                w.token("null");
            } else {
                w.write(pl.typeName());
                if (pl.varName() != null && !pl.varName().isEmpty()) {
                    w.space().write(pl.varName());
                }
            }
            if (pl.guard() != null) {
                w.space().token("when").space();
                exprs.emit(pl.guard());
            }
        } else {
            exprs.emit(label);
        }
    }

    /**
     * 对于 switch 表达式,将单条语句的 case 体扁平化(去掉不必要的块包装).
     *
     * @param body case 体中的语句列表
     * @return 简化后的单条语句或块语句
     */
    private Statement simplifyCaseBody(List<Statement> body) {
        if (body.size() == 1) {
            return body.get(0);
        }
        return new BlockStatement(body);
    }

    /**
     * 发射分支体.如果是块语句则直接发射,否则自动包裹在花括号中.
     *
     * @param stmt 分支体语句
     */
    private void emitBranched(Statement stmt) {
        if (stmt == null) {
            w.write("{}").newLine();
            return;
        }
        if (stmt instanceof BlockStatement) {
            emit(stmt);
        } else {
            w.write("{").newLine();
            w.indent();
            emit(stmt);
            w.dedent();
            w.write("}").newLine();
        }
    }
}
