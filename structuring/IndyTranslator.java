package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.semantic.SemanticAnnotation;
import com.bingbaihanji.bdec.semantic.SemanticTag;
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
 * 占位符字符串模式匹配。
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

    // ── 公开 API ──

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

        // 字符串拼接:交由 StringConcatRewriter 后续处理
        if (mName.contains("Concat") || mName.contains("concat")) {
            return translateAsRegularInvoke(insn);
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

    // ── 内部逻辑 ──

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
        // 方法引用
        String owner = simplifyClassName(implOwner);
        if (!operands.isEmpty() && operands.getFirst() instanceof Variable v
                && v.name() != null && !"this".equals(v.name())) {
            owner = v.name();
        }
        return LambdaExpr.methodRef(owner, implName, funcType);
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

    // ── 参数构建 ──

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

    /** 从实现方法描述符构建带类型的参数占位符 */
    static List<LambdaExpr.Param> buildParamsFromDescriptor(String methodDescriptor) {
        List<LambdaExpr.Param> params = new ArrayList<>();
        JavaType[] paramTypes = TypeResolver.parseMethodParameterTypes(methodDescriptor);
        for (int i = 0; i < paramTypes.length; i++) {
            params.add(new LambdaExpr.Param("arg" + i, paramTypes[i]));
        }
        return params;
    }

    // ── 工具方法 ──

    /** 检查方法名是否为已知的 SAM 名称 */
    static boolean isSamMethodName(String name) {
        return name != null && SAM_METHOD_NAMES.contains(name);
    }

    /** 检查类型是否类似函数式接口(java.util.function.* 或类似) */
    static boolean isFunctionalInterfaceLike(JavaType type) {
        if (type == null) return false;
        String desc = type.descriptor();
        if (desc == null) return false;
        return desc.contains("java/util/function/")
                || desc.contains("java/util/Comparator")
                || desc.contains("java/lang/Runnable")
                || desc.contains("java/util/concurrent/Callable");
    }

    /** 从函数式接口类型中提取简短显示名称 */
    static String functionalInterfaceShortName(JavaType type) {
        if (type == null) return null;
        String desc = type.descriptor();
        if (desc == null) return null;
        if (desc.startsWith("L") && desc.endsWith(";")) {
            String internal = desc.substring(1, desc.length() - 1);
            int slash = internal.lastIndexOf('/');
            return slash >= 0 ? internal.substring(slash + 1) : internal;
        }
        return desc;
    }

    /** 将完全限定内部类名简化为短名称 */
    static String simplifyClassName(String internalName) {
        if (internalName == null) return null;
        int slash = internalName.lastIndexOf('/');
        if (slash >= 0) return internalName.substring(slash + 1);
        int dollar = internalName.lastIndexOf('$');
        if (dollar >= 0) return internalName.substring(dollar + 1);
        return internalName;
    }

    /** 检查类型是否为 java.lang.Class 类型 */
    static boolean isClassType(JavaType type) {
        return type != null && type.kind() == TypeKind.CLASS
                && "java/lang/Class".equals(type.internalName());
    }
}
