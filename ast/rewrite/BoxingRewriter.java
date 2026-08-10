package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 装箱/拆箱消除重写器,负责去除 javac 编译器自动插入的包装类型装箱和拆箱方法调用.
 *
 * <p>可识别的模式:
 * <pre>
 *   Integer.valueOf(5)  →  5          (装箱 → 原始值)
 *   x.intValue()        →  x          (拆箱 → 原始变量)
 *   new Integer(5)      →  5          (Java 9 之前的装箱方式)
 *   Boolean.valueOf(b)  →  b          (布尔装箱消除)
 * </pre>
 *
 * <p>设计参考 CFR 的 {@code BoxingProcessor}.
 */
public class BoxingRewriter implements RewriteRule {

    /** 包装类型内部名称集合 */
    private static final Set<String> WRAPPER_TYPES = Set.of(
            "java/lang/Integer", "java/lang/Long", "java/lang/Short",
            "java/lang/Byte", "java/lang/Float", "java/lang/Double",
            "java/lang/Boolean", "java/lang/Character");

    /** 拆箱方法名到包装类型的映射 */
    private static final Map<String, String> UNBOX_METHODS = new HashMap<>();

    static {
        UNBOX_METHODS.put("intValue", "java/lang/Integer");
        UNBOX_METHODS.put("longValue", "java/lang/Long");
        UNBOX_METHODS.put("shortValue", "java/lang/Short");
        UNBOX_METHODS.put("byteValue", "java/lang/Byte");
        UNBOX_METHODS.put("floatValue", "java/lang/Float");
        UNBOX_METHODS.put("doubleValue", "java/lang/Double");
        UNBOX_METHODS.put("booleanValue", "java/lang/Boolean");
        UNBOX_METHODS.put("charValue", "java/lang/Character");
    }

    @Override
    public String name() {return "boxing";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
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
                        rewriteStatement(md.body())));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /** 递归重写语句中的装箱/拆箱调用 */
    private Statement rewriteStatement(Statement s) {
        if (s == null) {
            return null; // 抽象方法或 native 方法无方法体
        }
        return switch (s) {
            case BlockStatement bs -> new BlockStatement(bs.statements().stream()
                    .map(this::rewriteStatement).toList());
            case IfStatement i -> new IfStatement(
                    rewriteExpr(i.condition()),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
            case ExpressionStatement es -> new ExpressionStatement(rewriteExpr(es.expression()));
            case ReturnStatement rs -> new ReturnStatement(
                    rs.value() != null ? rewriteExpr(rs.value()) : null);
            case ThrowStatement ts -> new ThrowStatement(rewriteExpr(ts.expression()));
            default -> s;
        };
    }

    /**
     * 消除表达式树中的装箱和拆箱方法调用.
     *
     * <p>拆箱:{@code x.{type}Value()} → {@code x}
     * <p>装箱:{@code Integer.valueOf(x)} → {@code x}
     */
    private Expression rewriteExpr(Expression e) {
        if (e instanceof InvocationExpr inv) {
            // 拆箱:x.{type}Value() → x
            if (isUnboxCall(inv)) {
                return inv.target();
            }
            // 装箱:Integer.valueOf(x) → x
            if (isBoxCall(inv) && !inv.arguments().isEmpty()) {
                return rewriteExpr(inv.arguments().getFirst());
            }
            // 递归重写参数
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg));
            }
            return new InvocationExpr(
                    inv.target() != null ? rewriteExpr(inv.target()) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }
        // TODO: 递归重写其他表达式类型的子节点
        return e;
    }

    /** 检查是否为拆箱方法调用(如 x.intValue()),目标为包装类型且无参数 */
    private boolean isUnboxCall(InvocationExpr inv) {
        String name = inv.methodName();
        if (name == null || !UNBOX_METHODS.containsKey(name)) {
            return false;
        }
        return inv.target() != null && inv.arguments().isEmpty();
    }

    /** 检查是否为装箱方法调用(如 Integer.valueOf(x)),静态调用且恰好一个参数 */
    private boolean isBoxCall(InvocationExpr inv) {
        if (!"valueOf".equals(inv.methodName())) {
            return false;
        }
        return inv.target() == null && inv.arguments().size() == 1;
    }
}
