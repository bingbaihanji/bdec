package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.semantic.SemanticReconstructor;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Lambda 表达式重写器,检测 {@code invokedynamic} / {@code LambdaMetafactory} 模式,
 * 将其转换为 Java lambda 表达式.
 *
 * <p>在 IR/AST 层面的模式:
 * <pre>
 *   invokeDynamic("lambda$method$0", ...)  →  (args) -> body
 *   invokeDynamic("methodRef", ...)        →  Class::method
 * </pre>
 *
 * <p>利用类文件中的引导方法数据来解析函数式接口类型,目标方法句柄和捕获的参数.
 *
 * <p>设计参考 CFR 的 {@code LambdaExpressionRewriter}
 * 和 Vineflower 的 {@code LambdaProcessor}.
 */
public class LambdaRewriter implements RewriteRule {

    /** ACC_SYNTHETIC 标志位(0x1000) */
    private static final int ACC_SYNTHETIC = 0x1000;

    /** 在类文件中按名称查找 lambda 合成方法 */
    private static MethodModel findLambdaMethod(ClassFileModel cfm, String methodName) {
        for (MethodModel m : cfm.methods()) {
            if (methodName.equals(m.name())) {
                return m;
            }
        }
        return null;
    }

    /**
     * 若方法体为单条 "return expr;" 语句,则提取其中的表达式.
     * 若方法体包含多条语句或不是简单 return 语句,则返回 null.
     */
    private static Expression extractReturnExpr(BlockStatement body) {
        List<Statement> stmts = body.statements();
        if (stmts.size() == 1 && stmts.get(0) instanceof ReturnStatement rs
                && rs.value() != null) {
            return rs.value();
        }
        return null;
    }

    /**
     * 从块体中移除结尾的合成 return 语句.
     * 对于表达式式 lambda,将最后的 "return expr;" 转换为 "expr;".
     * 对于块式 lambda,移除结构化过程中添加的合成返回值.
     */
    private static BlockStatement stripOuterReturn(BlockStatement body) {
        List<Statement> stmts = new ArrayList<>(body.statements());
        // 移除非 void 方法中结构化器添加的结尾合成 "return null/0/false"
        if (!stmts.isEmpty()) {
            Statement last = stmts.get(stmts.size() - 1);
            if (last instanceof ReturnStatement rs && rs.value() instanceof LitExpr lit
                    && (lit.value() == null || lit.value() instanceof Number n
                    && n.intValue() == 0 || Boolean.FALSE.equals(lit.value()))) {
                stmts.remove(stmts.size() - 1);
            }
        }
        return new BlockStatement(stmts);
    }

    @Override
    public String name() {return "lambda";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<BootstrapMethodEntry> bootstrapMethods = context.bootstrapMethods();
        ClassFileModel cfm = context.classFile();

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, bootstrapMethods, cfm, context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    /** 重写类型声明,过滤掉 lambda 合成方法并重写方法体 */
    private TypeDeclaration rewriteType(TypeDeclaration td,
                                        List<BootstrapMethodEntry> bootstrapMethods,
                                        ClassFileModel cfm,
                                        DecompileContext ctx) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                // 过滤 lambda 合成方法:lambda$xxx$N 或方法引用桥接方法
                if (isLambdaSyntheticMethod(md)) {
                    continue;
                }
                members.add(new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                        md.parameterNames(), md.parameterTypes(),
                        md.body() != null
                                ? rewriteStatement(md.body(), bootstrapMethods, cfm, ctx)
                                : null));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /** 检查方法是否为 lambda 合成方法(lambda$xxx$N 或方法引用桥接方法) */
    private boolean isLambdaSyntheticMethod(MethodDeclaration md) {
        String name = md.name();
        if (name == null) {
            return false;
        }
        // lambda 体方法:lambda$enclosingMethod$N
        if (name.startsWith("lambda$")) {
            return true;
        }
        // 方法引用桥接:lambda$xxx$N 是最常见的模式
        // 其他合成模式在没有类上下文的情况下较难检测
        return false;
    }

    /** 递归重写语句,识别并转换 lambda 表达式,同时过滤孤立的 lambda 占位符 */
    private Statement rewriteStatement(Statement s,
                                       List<BootstrapMethodEntry> bootstrapMethods,
                                       ClassFileModel cfm,
                                       DecompileContext ctx) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(st -> rewriteStatement(st, bootstrapMethods, cfm, ctx))
                    .filter(st -> st != null)
                    .toList());
        }
        if (s instanceof ExpressionStatement es) {
            // 过滤孤立的 LambdaExpr 占位符(INDY 结果在另一个 BlockGroup 中被消费,
            // 导致此处作为独立 ExpressionStatement 残留).独立的 lambda 表达式
            // 或方法引用在 Java 中不是合法语句,必须被赋值/传参/返回/转型.
            Expression rewritten = rewriteExpr(es.expression(), bootstrapMethods, cfm, ctx);
            if (isOrphanLambda(rewritten)) {
                return null;
            }
            return new ExpressionStatement(rewritten);
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value(), bootstrapMethods, cfm, ctx) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(
                    rewriteExpr(i.condition(), bootstrapMethods, cfm, ctx),
                    rewriteStatement(i.thenBranch(), bootstrapMethods, cfm, ctx),
                    i.elseBranch() != null
                            ? rewriteStatement(i.elseBranch(), bootstrapMethods, cfm, ctx)
                            : null);
        }
        return s;
    }

    /** 检测表达式是否为孤立的 lambda 占位符(不应作为独立语句存在的 lambda) */
    private static boolean isOrphanLambda(Expression e) {
        if (e instanceof LambdaExpr lambda && !lambda.isMethodRef()
                && lambda.bodyExpr() instanceof VarExpr ve
                && ve.name().startsWith("/* lambda")) {
            return true;
        }
        return false;
    }

    /** 重写表达式,将 lambda 占位符替换为反编译的 lambda 体 */
    private Expression rewriteExpr(Expression e,
                                   List<BootstrapMethodEntry> bootstrapMethods,
                                   ClassFileModel cfm,
                                   DecompileContext ctx) {
        // 将 LambdaExpr 占位符替换为反编译后的 lambda 体.
        // 暂时禁用:需要先修复参数重建逻辑.
        // 占位符中的参数为空(来自 INDY 操作数),但反编译体引用的是 lambda 方法的参数名.
        // TODO: 从 lambda 方法而非 INDY 操作数重建参数.
        //if (e instanceof LambdaExpr lambda && !lambda.isMethodRef()
        //        && lambda.bodyExpr() instanceof VarExpr ve
        //        && ve.name().startsWith("/* lambda")) {
        //    return buildLambdaBody(lambda, ve.name(), cfm, ctx);
        //}

        if (e instanceof InvocationExpr inv) {
            String name = inv.methodName();
            if (name == null) {
                return e;
            }

            // 检测 lambda:方法名以 "lambda$" 开头
            if (name.startsWith("lambda$") && inv.target() == null) {
                return convertLambda(inv);
            }

            // 递归处理子表达式
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg, bootstrapMethods, cfm, ctx));
            }
            return new InvocationExpr(
                    inv.target() != null
                            ? rewriteExpr(inv.target(), bootstrapMethods, cfm, ctx) : null,
                    name, newArgs, inv.returnType());
        }
        return e;
    }

    /** 将 lambda 调用转换为 lambda 表达式占位符 */
    private Expression convertLambda(InvocationExpr inv) {
        String name = inv.methodName();
        String descriptor = name.replace("lambda$", "");

        List<Expression> args = inv.arguments();
        StringBuilder lambdaText = new StringBuilder("(");
        if (!args.isEmpty()) {
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    lambdaText.append(", ");
                }
                lambdaText.append("arg").append(i);
            }
        }
        lambdaText.append(") -> /* ").append(descriptor).append(" */");

        return new VarExpr(lambdaText.toString());
    }

    /**
     * 反编译 lambda 合成方法的方法体,生成真正的 LambdaExpr 代替占位符.
     */
    private LambdaExpr buildLambdaBody(LambdaExpr placeholder, String bodyHint,
                                       ClassFileModel cfm, DecompileContext ctx) {
        // 提取方法名:"/* lambda$methodName$N */" → "lambda$methodName$N"
        String methodName = bodyHint.replace("/* ", "").replace(" */", "").trim();
        if (methodName.isEmpty()) {
            return placeholder;
        }

        MethodModel lambdaMethod = findLambdaMethod(cfm, methodName);
        if (lambdaMethod == null) {
            return placeholder;
        }

        try {
            CfgBuilder cfgBuilder = new CfgBuilder();
            IrBuilder irBuilder = new IrBuilder();
            SemanticReconstructor sr = new SemanticReconstructor();
            ControlFlowStructurer structurer = new ControlFlowStructurer();

            ControlFlowGraph cfg = cfgBuilder.build(lambdaMethod);
            LinearIr ir = irBuilder.build(cfg, lambdaMethod,
                    cfm.constantPool(), cfm.bootstrapMethods());
            ir = sr.reconstruct(ir, lambdaMethod, cfg, cfm);
            StructuredMethod sm = structurer.structure(ir, ctx);

            if (sm.body() == null) {
                return placeholder;
            }

            // 尝试从反编译方法体中提取表达式体.
            // lambda 方法通常为:return expr;
            Expression bodyExpr = extractReturnExpr(sm.body());
            if (bodyExpr != null) {
                return LambdaExpr.expression(placeholder.parameters(),
                        bodyExpr, placeholder.functionalType());
            }

            // 块 lambda:直接使用方法体(去掉外层对最后一条语句的 return 包装)
            return LambdaExpr.block(placeholder.parameters(),
                    stripOuterReturn(sm.body()), placeholder.functionalType());
        } catch (Exception e) {
            return placeholder;
        }
    }
}
