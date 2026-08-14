package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.type.TypeResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * INDY (invokedynamic) 指令翻译器 — 将 INDY 字节码模式转换为
 * {@link LambdaExpr}(lambda 表达式)或方法引用.
 *
 * <p>从 BlockReducer 中提取,遵循 CFR 的 LambdaRewriter 模式:
 * 在 IR→AST 阶段解析引导方法信息,而非在后期 AST 重写中基于
 * 占位符字符串模式匹配.
 *
 * <p>检测优先级:
 * <ol>
 *   <li>字符串拼接(makeConcatWithConstants) → 委托回普通 INVOKE</li>
 *   <li>基于名称:含 "::" 或 "new " 前缀的方法引用</li>
 *   <li>引导方法解析:lambda$xxx$N → Lambda 占位符;
 *       {@code <init>} → 构造方法引用;其他 → 方法引用</li>
 *   <li>SAM 名称启发式回退</li>
 * </ol>
 */
public final class IndyTranslator {

    /** 常见函数式接口(单一抽象方法)的方法名集合 */
    private static final Set<String> SAM_METHOD_NAMES = Set.of(
            "apply", "accept", "test", "get", "run", "call",
            "compare", "thenApply", "thenAccept", "thenRun",
            "thenCompose", "applyAsInt", "applyAsLong", "applyAsDouble",
            "testAsInt", "testAsLong", "testAsDouble",
            "getAsBoolean", "getAsInt", "getAsLong", "getAsDouble",
            "compareTo", "andThen", "compose", "negate"
    );

    private final IndyAnnotationSource annotationSource;

    private final ExpressionSource expressionSource;

    /**
     * 构造 INDY 翻译器.
     *
     * @param annotationSource INDY 注解查询回调
     * @param expressionSource Value→Expression 翻译回调
     */
    public IndyTranslator(IndyAnnotationSource annotationSource,
                          ExpressionSource expressionSource) {
        this.annotationSource = annotationSource;
        this.expressionSource = expressionSource;
    }

    /** 基于名称的方法引用模式匹配 */
    private static Expression tryMethodRefByName(String mName, JavaType funcType) {
        if (mName.contains("::")) {
            String[] parts = mName.split("::", 2);
            return LambdaExpr.methodRef(parts[0],
                    parts.length > 1 ? parts[1] : "new", funcType);
        }
        if (mName.startsWith("new ")) {
            return LambdaExpr.methodRef(mName.substring(4), "new", funcType);
        }
        return null;
    }

    /** 将简单表达式转为方法引用 owner 所需的字符串表示 */
    private static String exprToString(Expression e) {
        if (e instanceof VarExpr v) {
            return v.name();
        }
        if (e instanceof FieldAccessExpr fa) {
            String target = fa.target() != null ? exprToString(fa.target()) : null;
            if (target != null) {
                return target + "." + fa.fieldName();
            }
            return fa.fieldName();
        }
        if (e instanceof InvocationExpr inv) {
            return inv.methodName();
        }
        return null;
    }

    // ── 公开 API ──

    /** 从实现方法描述符构建带类型的参数占位符 */
    static List<LambdaExpr.Param> buildParamsFromDescriptor(String methodDescriptor) {
        List<LambdaExpr.Param> params = new ArrayList<>();
        JavaType[] paramTypes = TypeResolver.parseMethodParameterTypes(methodDescriptor);
        for (int i = 0; i < paramTypes.length; i++) {
            params.add(new LambdaExpr.Param("arg" + i, paramTypes[i]));
        }
        return params;
    }

    // ── 内部逻辑 ──

    /** 检查方法名是否为已知的 SAM 名称 */
    static boolean isSamMethodName(String name) {
        return name != null && SAM_METHOD_NAMES.contains(name);
    }

    /** 检查类型是否类似函数式接口(java.util.function.* 或类似) */
    static boolean isFunctionalInterfaceLike(JavaType type) {
        if (type == null) {
            return false;
        }
        String desc = type.descriptor();
        if (desc == null) {
            return false;
        }
        return desc.contains("java/util/function/")
                || desc.contains("java/util/Comparator")
                || desc.contains("java/lang/Runnable")
                || desc.contains("java/util/concurrent/Callable");
    }

    /** 从函数式接口类型中提取简短显示名称 */
    static String functionalInterfaceShortName(JavaType type) {
        if (type == null) {
            return null;
        }
        String desc = type.descriptor();
        if (desc == null) {
            return null;
        }
        if (desc.startsWith("L") && desc.endsWith(";")) {
            String internal = desc.substring(1, desc.length() - 1);
            int slash = internal.lastIndexOf('/');
            return slash >= 0 ? internal.substring(slash + 1) : internal;
        }
        return desc;
    }

    /** 将完全限定内部类名简化为短名称 */
    static String simplifyClassName(String internalName) {
        if (internalName == null) {
            return null;
        }
        int slash = internalName.lastIndexOf('/');
        if (slash >= 0) {
            return internalName.substring(slash + 1);
        }
        int dollar = internalName.lastIndexOf('$');
        if (dollar >= 0) {
            return internalName.substring(dollar + 1);
        }
        return internalName;
    }

    /** 检查类型是否为 java.lang.Class 类型 */
    static boolean isClassType(JavaType type) {
        return type != null && type.kind() == TypeKind.CLASS
                && "java/lang/Class".equals(type.internalName());
    }

    // ── 参数构建 ──

    /** 检查表达式是否为 String 类型 */
    private static boolean isStringExpr(Expression e) {
        return e instanceof LitExpr lit && lit.value() instanceof String;
    }

    /** 从左到右构建二元 + 链 */
    private static Expression buildConcatChain(List<Expression> parts) {
        Expression result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = new com.bingbaihanji.bdec.ast.expr.BinExpr(
                    com.bingbaihanji.bdec.ast.expr.BinaryOperator.ADD,
                    result, parts.get(i));
        }
        return result;
    }

    // ── 工具方法 ──

    /**
     * 将 invokedynamic INVOKE 指令翻译为 LambdaExpr 或方法引用.
     *
     * @param insn INDY-tagged INVOKE 指令
     * @return LambdaExpr(表达式/块 lambda 或方法引用),或回退的 InvocationExpr
     */
    public Expression translate(IrInstruction insn) {
        String mName = insn.nameHint() != null ? insn.nameHint() : "lambda";
        JavaType funcType = insn.resultType();
        List<Value> operands = insn.operands();

        // 字符串拼接:解析 recipe 并直接构建拼接表达式
        if (mName.contains("Concat") || mName.contains("concat")) {
            return translateStringConcat(insn);
        }

        // 模式匹配 switch:typeSwitch/enumSwitch 的 INDY 调用.
        // 返回占位符表达式(switch 结果索引),保持 int 类型,
        // 使 case 标签(-1/0/1/2)与判别式类型匹配.
        // 后续 SwitchPatternMatchRewriter 将索引映射为类型模式.
        if ("typeSwitch".equals(mName) || "enumSwitch".equals(mName)) {
            return new VarExpr("switchKey");
        }

        // 从语义注解中读取已解析的引导方法信息
        String implName = annotationSource.getIndyAnnotation(insn, "implName");
        String implOwner = annotationSource.getIndyAnnotation(insn, "implOwner");
        String implDescriptor = annotationSource.getIndyAnnotation(insn, "implDescriptor");

        // 从操作数构建参数列表(捕获变量 + 工厂参数 → lambda 参数)
        List<LambdaExpr.Param> params = buildParams(operands);

        // 当 INDY 操作数无法提供参数信息时(无捕获变量的 lambda),
        // 使用实现方法描述符生成带类型的参数占位符
        if (params.isEmpty() && implDescriptor != null && !implDescriptor.isEmpty()) {
            params = buildParamsFromDescriptor(implDescriptor);
        }

        // 基于名称的方法引用("::" 或 "new " 前缀)
        Expression methodRef = tryMethodRefByName(mName, funcType);
        if (methodRef != null) {
            return methodRef;
        }

        // 已解析的引导方法信息:区分 lambda 和方法引用
        if (implName != null && !implName.isEmpty() && implOwner != null) {
            return translateByBootstrap(implName, implOwner, operands, params, funcType);
        }

        // 回退启发式
        if (isSamMethodName(mName) && isFunctionalInterfaceLike(funcType)) {
            String owner = functionalInterfaceShortName(funcType);
            if (owner != null && !owner.isEmpty()) {
                return LambdaExpr.methodRef(owner, mName, funcType);
            }
        }

        // Lambda 占位符回退
        return LambdaExpr.placeholder(params, "/* " + mName + " */", funcType);
    }

    /** 使用已解析的引导方法信息进行翻译 */
    private Expression translateByBootstrap(String implName, String implOwner,
                                            List<Value> operands,
                                            List<LambdaExpr.Param> params,
                                            JavaType funcType) {
        if (implName.startsWith("lambda$")) {
            return LambdaExpr.placeholder(params, "/* " + implName + " */", funcType);
        }
        if ("<init>".equals(implName)) {
            return LambdaExpr.methodRef(simplifyClassName(implOwner), "new", funcType);
        }
        // 方法引用:优先使用捕获的接收器作为 owner
        // 例如 System.out::println → implOwner=PrintStream,但接收器是 System.out
        String owner = resolveMethodRefOwner(implOwner, operands);
        return LambdaExpr.methodRef(owner, implName, funcType);
    }

    /**
     * 解析方法引用的 owner 字符串.
     *
     * <p>对于绑定的方法引用(如 System.out::println),捕获的接收器(第一个操作数)
     * 决定了显示名称.对于非绑定的方法引用(如 String::toUpperCase),使用实现类名.
     *
     * @param implOwner 实现方法所属类的内部名称(如 java/io/PrintStream)
     * @param operands  INDY 动态参数(包含绑定的接收器或 lambda 参数)
     * @return 方法引用 owner 的显示字符串
     */
    private String resolveMethodRefOwner(String implOwner, List<Value> operands) {
        if (operands.isEmpty()) {
            return simplifyClassName(implOwner);
        }
        Value first = operands.getFirst();
        // 变量:直接使用变量名(但不包括 this,因为 this 是隐式的)
        if (first instanceof Variable v
                && v.name() != null && !"this".equals(v.name())) {
            return v.name();
        }
        // 非变量(InstructionRef 来自 GETSTATIC/GETFIELD 等):
        // 将值转换为表达式,提取字符串表示
        // 例如:GETSTATIC System.out → "System.out"
        if (!(first instanceof Variable)) {
            try {
                Expression expr = expressionSource.valueToExpr(first);
                String exprStr = exprToString(expr);
                if (exprStr != null) {
                    return exprStr;
                }
            } catch (Exception ignored) {
                // 转换失败时回退到类名
            }
        }
        return simplifyClassName(implOwner);
    }

    /** 回退:将 INDY 当作普通方法调用处理 */
    private Expression translateAsRegularInvoke(IrInstruction insn) {
        String mName = insn.nameHint() != null ? insn.nameHint() : "method";
        List<Expression> args = new ArrayList<>();
        for (Value op : insn.operands()) {
            args.add(expressionSource.valueToExpr(op));
        }
        return new InvocationExpr(null, mName, args, insn.resultType());
    }

    /** 将 makeConcatWithConstants INDY 翻译为字符串拼接链.
     *  解析 recipe(如 " ")并在动态参数之间插入静态字符串片段. */
    private Expression translateStringConcat(IrInstruction insn) {
        String recipe = annotationSource.getIndyAnnotation(insn, "recipe");
        List<Value> operands = insn.operands();

        // 无 recipe 时回退:简单拼接所有参数
        if (recipe == null || recipe.isEmpty()) {
            return buildSimpleConcat(operands);
        }

        // 解析 recipe:按  分割,得到静态片段[0], 动态占位[1...],静态片段[1...]
        // recipe " " → ["", " ", ""] (首尾为空,中间 " " 是分隔符)
        String[] parts = recipe.split("", -1);
        List<Expression> concatArgs = new ArrayList<>();

        int argIdx = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!part.isEmpty()) {
                // 将  分隔的多个静态部分展开
                //(javac 有时用  作为片段分隔符)
                String[] subParts = part.split("", -1);
                for (String sp : subParts) {
                    if (!sp.isEmpty()) {
                        concatArgs.add(new com.bingbaihanji.bdec.ast.expr.LitExpr(
                                sp, JavaType.classType("java/lang/String")));
                    }
                }
            }
            // 在静态部分后插入动态参数(最后一个静态部分之后没有动态参数)
            if (i < parts.length - 1 && argIdx < operands.size()) {
                concatArgs.add(expressionSource.valueToExpr(operands.get(argIdx++)));
            }
        }

        if (concatArgs.isEmpty()) {
            return new LitExpr("", JavaType.classType("java/lang/String"));
        }
        // recipe 已确保类型正确,不需要强制添加 "" 前缀
        return buildConcatChain(concatArgs);
    }

    /** 简单拼接(无 recipe):用 + 连接所有参数 */
    private Expression buildSimpleConcat(List<Value> operands) {
        List<Expression> args = new ArrayList<>();
        for (Value op : operands) {
            args.add(expressionSource.valueToExpr(op));
        }
        if (args.isEmpty()) {
            return new LitExpr("", JavaType.classType("java/lang/String"));
        }
        if (!isStringExpr(args.get(0))) {
            args.addFirst(new LitExpr("", JavaType.classType("java/lang/String")));
        }
        return buildConcatChain(args);
    }

    /** 从 INDY 操作数构建参数列表(捕获变量 + 工厂参数 → lambda 参数) */
    List<LambdaExpr.Param> buildParams(List<Value> operands) {
        List<LambdaExpr.Param> params = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Value op = operands.get(i);
            JavaType pt = op.type();
            String pName = "arg" + i;
            if (op instanceof Variable v) {
                String vn = v.name();
                if (vn != null && !vn.startsWith("var") && !"this".equals(vn)) {
                    pName = vn;
                }
            }
            params.add(new LambdaExpr.Param(pName, pt));
        }
        return params;
    }

    /** 需要 INDY 注解键名的回调接口 */
    @FunctionalInterface
    public interface IndyAnnotationSource {

        /** 获取 INDY 指令的指定注解字符串值 */
        String getIndyAnnotation(IrInstruction insn, String key);
    }

    /** 需要表达式翻译的回调接口 */
    @FunctionalInterface
    public interface ExpressionSource {

        /** 将 IR Value 转换为 AST Expression */
        Expression valueToExpr(Value v);
    }
}
