package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 局部类 this$0 清理重写器——未使用外围实例的内部类省略合成字段与参数.
 *
 * <p>javac 为局部类生成 this$0 字段与构造参数(捕获外围 this),
 * 但类体若不引用外围实例,反编译输出中应省略:
 * <pre>
 *   class LocalClass {                       class LocalClass {
 *       LocalClass(TestClass2 this$0) {}        public void run() {...}
 *       public void run() {...}            →  }
 *   }
 * </pre>
 * 同时清理实例化处 {@code new LocalClass(this)} → {@code new LocalClass()}.
 * 若类体(构造器除外)引用 this$0,则保持原样(如 InnerClass.display 的
 * this$0.counter 访问).
 */
public class LocalClassRewriter implements RewriteRule {

    @Override
    public String name() {return "local-class";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        // 找出可省略 this$0 的内部类(含嵌套):构造器含 this$ 参数且
        // 方法体(构造器除外)不引用该参数.
        Map<String, String> cleanable = new HashMap<>(); // simpleName → this$ 参数名
        for (TypeDeclaration td : unit.types()) {
            collectCleanable(td, cleanable);
        }
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(processType(td, cleanable));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types,
                unit.innerClassNames());
    }

    /** 递归处理类型(含嵌套内部类):可清理的清理自身,其余重写使用点 */
    private TypeDeclaration processType(TypeDeclaration td,
                                        Map<String, String> cleanable) {
        if (cleanable.containsKey(td.simpleName())) {
            return cleanType(td, cleanable.get(td.simpleName()));
        }
        // 重写成员中的使用点,并递归处理嵌套类型
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof TypeDeclaration nested) {
                members.add(processType(nested, cleanable));
            } else if (m instanceof MethodDeclaration md && md.body() != null) {
                Statement body = rewriteUsageStmt(md.body(), cleanable);
                members.add(withBody(md, body instanceof BlockStatement bs ? bs : body));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 递归收集可清理的内部类(含嵌套) */
    private void collectCleanable(TypeDeclaration td,
                                  Map<String, String> cleanable) {
        String thisParam = findThisParam(td);
        if (thisParam != null && !usesThisField(td, thisParam)) {
            cleanable.put(td.simpleName(), thisParam);
        }
        for (AstNode m : td.children()) {
            if (m instanceof TypeDeclaration nested) {
                collectCleanable(nested, cleanable);
            }
        }
    }

    /** 查找构造器的 this$ 合成参数名 */
    private String findThisParam(TypeDeclaration td) {
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md
                    && td.simpleName().equals(md.name())) {
                for (String p : md.parameterNames()) {
                    if (p.startsWith("this$")) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** 类体(构造器除外)是否引用 this$ 字段 */
    private boolean usesThisField(TypeDeclaration td, String thisField) {
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null
                    && !td.simpleName().equals(md.name())) { // 跳过构造器
                if (stmtUsesThis(md.body(), thisField)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean stmtUsesThis(Statement s, String thisField) {
        if (s instanceof ExpressionStatement es) {
            return exprUsesThis(es.expression(), thisField);
        }
        if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            return exprUsesThis(vd.initializer(), thisField);
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement rs && rs.value() != null) {
            return exprUsesThis(rs.value(), thisField);
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.IfStatement i) {
            return exprUsesThis(i.condition(), thisField)
                    || stmtUsesThis(i.thenBranch(), thisField)
                    || (i.elseBranch() != null && stmtUsesThis(i.elseBranch(), thisField));
        }
        if (s instanceof BlockStatement bs) {
            for (Statement c : bs.statements()) {
                if (stmtUsesThis(c, thisField)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean exprUsesThis(Expression e, String thisField) {
        if (e == null) {
            return false;
        }
        if (e instanceof VarExpr v) {
            return thisField.equals(v.name());
        }
        if (e instanceof FieldAccessExpr fa) {
            return thisField.equals(fa.fieldName())
                    || (fa.target() instanceof VarExpr tv && thisField.equals(tv.name()))
                    || exprUsesThis(fa.target(), thisField);
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.BinExpr b) {
            return exprUsesThis(b.left(), thisField) || exprUsesThis(b.right(), thisField);
        }
        if (e instanceof InvocationExpr inv) {
            if (exprUsesThis(inv.target(), thisField)) {
                return true;
            }
            for (Expression a : inv.arguments()) {
                if (exprUsesThis(a, thisField)) {
                    return true;
                }
            }
        }
        if (e instanceof AssignExpr a) {
            return exprUsesThis(a.target(), thisField) || exprUsesThis(a.value(), thisField);
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.CastExpr cast) {
            return exprUsesThis(cast.operand(), thisField);
        }
        return false;
    }

    /** 清理内部类:移除 this$0 字段、构造器参数与赋值 */
    private TypeDeclaration cleanType(TypeDeclaration td, String thisField) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && thisField.equals(fd.name())) {
                continue; // 移除 this$0 字段
            }
            if (m instanceof MethodDeclaration md && md.body() != null
                    && td.simpleName().equals(md.name())) {
                // 构造器:移除 this$0 参数与 this.this$0 = this$0; 赋值
                members.add(cleanConstructor(md, thisField));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    private MethodDeclaration cleanConstructor(MethodDeclaration md, String thisField) {
        // 移除 this$0 参数(第一个参数)
        String[] names = md.parameterNames();
        JavaType[] types = md.parameterTypes();
        String[] paramAnns = md.parameterAnnotations();
        List<String> newNames = new ArrayList<>();
        List<JavaType> newTypes = new ArrayList<>();
        List<String> newAnns = paramAnns != null ? new ArrayList<>() : null;
        for (int i = 0; i < names.length; i++) {
            if (!(i == 0 && types[i] != null
                    && types[i].internalName() != null
                    && names[i].equals(thisField))) {
                newNames.add(names[i]);
                newTypes.add(types[i]);
                if (newAnns != null) {
                    newAnns.add(paramAnns[i]);
                }
            }
        }
        Statement body = md.body();
        if (body instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>();
            for (Statement s : bs.statements()) {
                // this.this$0 = this$0; 赋值
                if (s instanceof ExpressionStatement es
                        && es.expression() instanceof AssignExpr a
                        && a.target() instanceof FieldAccessExpr fa
                        && thisField.equals(fa.fieldName())) {
                    continue;
                }
                stmts.add(s);
            }
            body = new BlockStatement(stmts);
        }
        return withParamsAndBody(md, newNames.toArray(String[]::new),
                newTypes.toArray(JavaType[]::new),
                newAnns != null ? newAnns.toArray(String[]::new) : null, body);
    }

    /** 重写外层方法体中的实例化:new LocalClass(this) → new LocalClass() */
    private TypeDeclaration rewriteUsages(TypeDeclaration td,
                                          Map<String, String> cleanable) {
        if (cleanable.isEmpty()) {
            return td;
        }
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                Statement body = rewriteUsageStmt(md.body(), cleanable);
                members.add(withBody(md, body instanceof BlockStatement bs ? bs : body));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    private Statement rewriteUsageStmt(Statement s, Map<String, String> cleanable) {
        if (s instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>();
            for (Statement c : bs.statements()) {
                stmts.add(rewriteUsageStmt(c, cleanable));
            }
            return new BlockStatement(stmts);
        }
        if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            return new VariableDeclaration(vd.type(), vd.name(),
                    rewriteUsageExpr(vd.initializer(), cleanable),
                    vd.typeAnnotations());
        }
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(rewriteUsageExpr(es.expression(), cleanable));
        }
        return s;
    }

    private Expression rewriteUsageExpr(Expression e, Map<String, String> cleanable) {
        if (e instanceof NewExpr ne && ne.instantiatedType() != null) {
            String internal = ne.instantiatedType().internalName();
            String simple = internal != null
                    ? internal.substring(internal.lastIndexOf('/') + 1) : "";
            // 字节码内部名(TestClass2$1LocalClass)与显示名(LocalClass)匹配
            String cleanKey = null;
            if (cleanable.containsKey(simple)) {
                cleanKey = simple;
            } else {
                for (String key : cleanable.keySet()) {
                    if (simple.endsWith(key)) {
                        cleanKey = key;
                        break;
                    }
                }
            }
            if (cleanKey != null) {
                // 去掉首参 this
                List<Expression> args = new ArrayList<>(ne.constructorArgs());
                if (!args.isEmpty() && args.getFirst() instanceof VarExpr v
                        && "this".equals(v.name())) {
                    args.removeFirst();
                }
                return new NewExpr(ne.instantiatedType(), ne.dimensions(), args,
                        ne.anonymousBody(), ne.arrayInitializer(), ne.typeAnnotations());
            }
        }
        if (e instanceof InvocationExpr inv) {
            List<Expression> args = new ArrayList<>();
            for (Expression a : inv.arguments()) {
                args.add(rewriteUsageExpr(a, cleanable));
            }
            return new InvocationExpr(inv.target(), inv.methodName(), args, inv.returnType());
        }
        return e;
    }
}
