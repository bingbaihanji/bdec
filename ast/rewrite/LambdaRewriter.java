package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
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
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.util.ParameterNameResolver;

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
     * 也尝试将 boolean if-else-return 模式折叠为表达式:
     * <pre>
     *   if (cond) { return true; } return false;  →  return cond;
     *   if (cond) { return false; } return true;  →  return !cond;
     * </pre>
     * 若方法体包含多条语句或不是简单 return 语句,则返回 null.
     */
    private static Expression extractReturnExpr(BlockStatement body) {
        List<Statement> stmts = body.statements();
        // 单条 return expr; 直接提取
        if (stmts.size() == 1 && stmts.get(0) instanceof ReturnStatement rs
                && rs.value() != null) {
            return rs.value();
        }
        // boolean if-else-return 折叠:if (cond) { return true; } return false;
        Expression collapsed = collapseBooleanIfReturn(stmts);
        if (collapsed != null) {
            return collapsed;
        }
        // 处理 if(cond) return false; 无 else 且无尾随 return 的情况
        // (共享合并块中的 return 被 if-else 结构吸收后导致)
        collapsed = collapseIfReturnFalse(body);
        if (collapsed != null) {
            return collapsed;
        }
        return null;
    }

    /**
     * 识别并折叠 boolean if-else-return 模式.
     * 模式1:if (cond) { return true; } return false; → cond
     * 模式2:if (cond) { return false; } return true; → !cond
     * 模式3:if (cond) { return true; } else { return false; } → cond
     * 模式4:if (cond) { return false; } else { return true; } → !cond
     */
    private static Expression collapseBooleanIfReturn(List<Statement> stmts) {
        // 模式1/2:两条语句(if + trailing return)
        if (stmts.size() == 2
                && stmts.get(0) instanceof IfStatement ifStmt
                && ifStmt.elseBranch() == null
                && stmts.get(1) instanceof ReturnStatement trailingReturn) {
            ReturnStatement thenRet = getSingleReturnFromBlock(ifStmt.thenBranch());
            if (thenRet == null) {
                return null;
            }
            return tryCollapseBoolean(ifStmt.condition(), thenRet, trailingReturn);
        }
        // 模式3/4:单条 if-else 语句
        if (stmts.size() == 1
                && stmts.get(0) instanceof IfStatement ifStmt
                && ifStmt.elseBranch() != null) {
            ReturnStatement thenRet = getSingleReturnFromBlock(ifStmt.thenBranch());
            ReturnStatement elseRet = getSingleReturnFromBlock(ifStmt.elseBranch());
            if (thenRet == null || elseRet == null) {
                return null;
            }
            return tryCollapseBoolean(ifStmt.condition(), thenRet, elseRet);
        }
        return null;
    }

    /** 从语句块中提取单条 return 语句,块内必须有且仅有一条 return */
    private static ReturnStatement getSingleReturnFromBlock(Statement s) {
        if (s instanceof ReturnStatement rs && rs.value() != null) {
            return rs;
        }
        if (s instanceof BlockStatement bs && bs.statements().size() == 1
                && bs.statements().get(0) instanceof ReturnStatement rs
                && rs.value() != null) {
            return rs;
        }
        return null;
    }

    /**
     * 处理 if(cond) return false; (无 else 且无尾随 return)的特殊情况.
     * 当共享合并块中有 return true 且被 if-else 结构吸收时,
     * if 分支体退化为仅有 then 无 else 的形式.此时将条件取反即可得到表达式形式.
     */
    private static Expression collapseIfReturnFalse(Statement s) {
        if (!(s instanceof BlockStatement bs) || bs.statements().size() != 1) {
            return null;
        }
        Statement only = bs.statements().get(0);
        if (!(only instanceof IfStatement ifStmt) || ifStmt.elseBranch() != null) {
            return null;
        }
        ReturnStatement thenRet = getSingleReturnFromBlock(ifStmt.thenBranch());
        if (thenRet == null) {
            return null;
        }
        // if (cond) { return false; } → !cond
        if (isBooleanLiteral(thenRet.value(), false)) {
            return new com.bingbaihanji.bdec.ast.expr.UnExpr(
                    com.bingbaihanji.bdec.ast.expr.UnaryOperator.NOT,
                    ifStmt.condition());
        }
        // if (cond) { return true; } → cond
        if (isBooleanLiteral(thenRet.value(), true)) {
            return ifStmt.condition();
        }
        return null;
    }

    /**
     * 尝试折叠 boolean 模式:thenRet 和 trailingRet 必须为一个 true 一个 false.
     * @return cond 或 !cond,或 null
     */
    private static Expression tryCollapseBoolean(Expression cond,
                                                 ReturnStatement thenRet, ReturnStatement trailingRet) {
        boolean thenIsTrue = isBooleanLiteral(thenRet.value(), true);
        boolean thenIsFalse = isBooleanLiteral(thenRet.value(), false);
        boolean otherIsTrue = isBooleanLiteral(trailingRet.value(), true);
        boolean otherIsFalse = isBooleanLiteral(trailingRet.value(), false);

        // then=true, other=false → cond (then 分支是 true,所以条件为真时执行 then)
        if (thenIsTrue && otherIsFalse) {
            return cond;
        }
        // then=false, other=true → !cond
        if (thenIsFalse && otherIsTrue) {
            return new com.bingbaihanji.bdec.ast.expr.UnExpr(
                    com.bingbaihanji.bdec.ast.expr.UnaryOperator.NOT, cond);
        }
        return null;
    }

    private static boolean isBooleanLiteral(Expression e, boolean expected) {
        if (e instanceof LitExpr lit && lit.value() instanceof Boolean b) {
            return b.booleanValue() == expected;
        }
        return false;
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

    /** 检测表达式是否为孤立的 lambda 占位符(不应作为独立语句存在的 lambda) */
    private static boolean isOrphanLambda(Expression e) {
        if (e instanceof LambdaExpr lambda && !lambda.isMethodRef()
                && lambda.bodyExpr() instanceof VarExpr ve
                && ve.name().startsWith("/* lambda")) {
            return true;
        }
        return false;
    }

    /** 从 lambda 合成方法中提取参数列表(类型优先使用 Signature 属性,名称来自 LVT) */
    private static List<LambdaExpr.Param> buildLambdaParams(MethodModel m) {
        List<LambdaExpr.Param> params = new ArrayList<>();
        // 优先使用 Signature 属性获取泛型参数类型(而非擦除后的描述符类型).
        // 例如 lambda 方法描述符为 (Object,Object)Object,
        // 但 Signature 为 (Integer,Integer)Integer.
        JavaType[] paramTypes = m.parameterTypes();
        String sig = m.signature();
        if (sig != null && !sig.isEmpty()) {
            JavaType[] sigTypes = com.bingbaihanji.bdec.bytecode.parser.SignatureParser
                    .parseMethodSignature(sig);
            if (sigTypes != null && sigTypes.length == paramTypes.length + 1) {
                // sigTypes = [paramTypes..., returnType]
                paramTypes = new JavaType[paramTypes.length];
                System.arraycopy(sigTypes, 0, paramTypes, 0, paramTypes.length);
            }
        }
        String[] names = ParameterNameResolver.resolveNames(m, "arg");
        for (int i = 0; i < paramTypes.length; i++) {
            params.add(new LambdaExpr.Param(names[i], paramTypes[i]));
        }
        return params;
    }


    @Override
    public String name() {return "lambda";}

    @Override
    public RewriteRuleKind kind() {return RewriteRuleKind.LAMBDA;}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<BootstrapMethodEntry> bootstrapMethods = context.bootstrapMethods();
        ClassFileModel cfm = context.classFile();

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, bootstrapMethods, cfm, context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
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
                members.add(withBody(md, md.body() != null
                                ? rewriteStatement(md.body(), bootstrapMethods, cfm, ctx)
                                : null));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
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
            // 过滤孤立的 LambdaExpr 占位符
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
        if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            Expression newInit = rewriteExpr(vd.initializer(), bootstrapMethods, cfm, ctx);
            if (newInit != vd.initializer()) {
                return new VariableDeclaration(vd.type(), vd.name(), newInit, vd.typeAnnotations());
            }
        }
        return s;
    }

    /** 重写表达式,将 lambda 占位符替换为反编译的 lambda 体. */
    private Expression rewriteExpr(Expression e,
                                   List<BootstrapMethodEntry> bootstrapMethods,
                                   ClassFileModel cfm,
                                   DecompileContext ctx) {
        // 将 LambdaExpr 占位符替换为反编译后的 lambda 体(参数从 lambda method 提取)
        if (e instanceof LambdaExpr lambda && !lambda.isMethodRef()
                && lambda.bodyExpr() instanceof VarExpr ve
                && ve.name().startsWith("/* lambda")) {
            return buildLambdaBody(lambda, ve.name(), cfm, ctx);
        }

        // 递归处理赋值表达式:lhs = rhs (lambda 可能出现在 rhs 中)
        if (e instanceof AssignExpr assign) {
            Expression newTarget = rewriteExpr(assign.target(), bootstrapMethods, cfm, ctx);
            Expression newValue = rewriteExpr(assign.value(), bootstrapMethods, cfm, ctx);
            if (newTarget != assign.target() || newValue != assign.value()) {
                return new AssignExpr(newTarget, newValue);
            }
            return e;
        }

        // 递归处理类型转换:(Type) expr
        if (e instanceof CastExpr cast) {
            Expression newOperand = rewriteExpr(cast.operand(), bootstrapMethods, cfm, ctx);
            if (newOperand != cast.operand()) {
                return new CastExpr(cast.targetType(), newOperand,
                        cast.typeAnnotations());
            }
            return e;
        }

        // 递归处理三元表达式:cond ? trueExpr : falseExpr
        if (e instanceof CondExpr cond) {
            Expression newCond = rewriteExpr(cond.condition(), bootstrapMethods, cfm, ctx);
            Expression newTrue = rewriteExpr(cond.trueExpr(), bootstrapMethods, cfm, ctx);
            Expression newFalse = rewriteExpr(cond.falseExpr(), bootstrapMethods, cfm, ctx);
            if (newCond != cond.condition() || newTrue != cond.trueExpr()
                    || newFalse != cond.falseExpr()) {
                return new CondExpr(newCond, newTrue, newFalse);
            }
            return e;
        }

        // 递归处理二元表达式
        if (e instanceof BinExpr bin) {
            Expression newLeft = rewriteExpr(bin.left(), bootstrapMethods, cfm, ctx);
            Expression newRight = rewriteExpr(bin.right(), bootstrapMethods, cfm, ctx);
            if (newLeft != bin.left() || newRight != bin.right()) {
                return new BinExpr(bin.operator(), newLeft, newRight);
            }
            return e;
        }

        // 递归处理一元表达式
        if (e instanceof UnExpr un) {
            Expression newOp = rewriteExpr(un.operand(), bootstrapMethods, cfm, ctx);
            if (newOp != un.operand()) {
                return new UnExpr(un.operator(), newOp);
            }
            return e;
        }

        // 递归处理字段访问
        if (e instanceof FieldAccessExpr fa) {
            Expression newTarget = fa.target() != null
                    ? rewriteExpr(fa.target(), bootstrapMethods, cfm, ctx) : null;
            if (newTarget != fa.target()) {
                return new FieldAccessExpr(newTarget, fa.fieldName());
            }
            return e;
        }

        // 递归处理数组访问
        if (e instanceof ArrayAccessExpr aa) {
            Expression newArray = rewriteExpr(aa.array(), bootstrapMethods, cfm, ctx);
            Expression newIndex = rewriteExpr(aa.index(), bootstrapMethods, cfm, ctx);
            if (newArray != aa.array() || newIndex != aa.index()) {
                return new ArrayAccessExpr(newArray, newIndex);
            }
            return e;
        }

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
     * 参数从 lambda METHOD 的 MethodModel 中提取,因为 INDY 操作数只包含捕获变量.
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

        // 从 lambda 方法自身提取参数类型和名称
        List<LambdaExpr.Param> params = buildLambdaParams(lambdaMethod);

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
                return LambdaExpr.expression(params, bodyExpr,
                        placeholder.functionalType());
            }

            // 块 lambda:直接使用方法体(去掉外层对最后一条语句的 return 包装)
            return LambdaExpr.block(params,
                    stripOuterReturn(sm.body()), placeholder.functionalType());
        } catch (Exception e) {
            return placeholder;
        }
    }
}
