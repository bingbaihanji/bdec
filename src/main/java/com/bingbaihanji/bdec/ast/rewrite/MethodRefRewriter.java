package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法引用重写器,检测解析为方法引用的 {@code invokedynamic} 模式,
 * 将其转换为 Java 的 {@code ::} 语法.
 *
 * <p>支持四种方法引用类型:
 * <pre>
 *   静态:      ClassName::staticMethod      (INVOKESTATIC)
 *   绑定:      expr::instanceMethod          (INVOKEVIRTUAL,接收者已捕获)
 *   未绑定:    ClassName::instanceMethod     (INVOKEVIRTUAL,接收者为第 1 个参数)
 *   构造器:    ClassName::new                (NEW + INVOKESPECIAL 初始化)
 * </pre>
 *
 * <p>设计参考 CFR 的 lambda 处理和 Vineflower 的 {@code LambdaProcessor}.
 */
public class MethodRefRewriter implements RewriteRule {

    @Override
    public String name() {return "method-ref";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<BootstrapMethodEntry> bootstrapMethods = context.bootstrapMethods();
        if (bootstrapMethods == null || bootstrapMethods.isEmpty()) {
            return unit;
        }

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /** 递归重写类型声明中的每个方法体 */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        md.body() != null ? rewriteStatement(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /** 递归重写语句,识别方法引用调用 */
    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(this::rewriteStatement).toList());
        }
        if (s instanceof ExpressionStatement es) {
            Expression rewritten = rewriteExpr(es.expression());
            return new ExpressionStatement(rewritten);
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value()) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(rewriteExpr(i.condition()),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        return s;
    }

    /** 重写表达式,检测方法引用模式并转换为 :: 语法 */
    private Expression rewriteExpr(Expression e) {
        if (e instanceof InvocationExpr inv) {
            // 先递归处理子表达式
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            Expression newTarget = inv.target() != null
                    ? rewriteExpr(inv.target()) : null;

            // 检测方法引用模式
            String name = inv.methodName();
            if (name != null && newTarget == null && !newArgs.isEmpty()) {
                // 可能为方法引用:invokedynamic 带静态参数
                Expression methodRef = tryConvertMethodRef(name, newArgs);
                if (methodRef != null) {
                    return methodRef;
                }
            }

            return new InvocationExpr(newTarget, name, newArgs, inv.returnType());
        }
        return e;
    }

    /**
     * 尝试将 invokedynamic 调用转换为 {@code ::} 方法引用.
     * 模式:invokedynamic 的名称包含 "$$" 或以已知的 lambda/SAM 前缀开头.
     */
    private Expression tryConvertMethodRef(String name, List<Expression> args) {
        // 模式一:new ClassName() 构造器引用
        if (name.startsWith("new") && args.size() == 1
                && args.get(0) instanceof VarExpr vx) {
            return new VarExpr(vx.name() + "::new");
        }

        // 模式二:静态方法引用 Class::method
        if (name.contains("::")) {
            return new VarExpr(name); // 已是格式化后的结果
        }

        // 模式三:来自 indy 的一般方法引用模式
        // methodName$hash 或 lambda$method$N → 提取
        if (name.contains("$") && args.size() >= 1) {
            // 尝试格式化为 Class::method
            String clean = name.replace("lambda$", "").replace("$", "::");
            if (clean.contains("::")) {
                return new VarExpr(clean);
            }
        }

        return null;
    }
}
