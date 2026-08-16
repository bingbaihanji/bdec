package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.AnnotationRenderer;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CondExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.bytecode.model.TypePathElement;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.List;

/**
 * 表达式结构与布尔性判断助手(从 BlockReducer 抽取的纯静态方法).
 */
public final class ExprCleanup {

    private ExprCleanup() {
    }

    /**
     * 渲染 Variable 携带的 JSR-308 类型注解(0x40 局部变量),
     * 按类型路径分组为渲染行映射.
     * 注解类型名取简单名(与声明点同包/已导入假设一致).
     */
    public static java.util.Map<java.util.List<TypePathElement>,
            List<String>> renderVarTypeAnnotations(Variable v) {
        if (v.typeAnnotations() == null || v.typeAnnotations().isEmpty()) {
            return java.util.Map.of();
        }
        return AnnotationRenderer.groupByTypePath(v.typeAnnotations());
    }

    /**
     * 目标类型绑定的菱形推断:声明类型带泛型实参且初始化式为泛型类的
     * {@code new} 时置菱形标志——{@code List<String> x = new ArrayList<>()}
     * 让 javac 从赋值目标重推断类型实参.仅当新对象类本身是泛型类时才置位.
     */
    public static Expression markTargetDiamond(Expression rhs, JavaType declType) {
        if (!(rhs instanceof NewExpr ne)
                || declType == null || declType.typeArguments().isEmpty()
                || ne.instantiatedType() == null
                || ne.instantiatedType().internalName() == null) {
            return rhs;
        }
        if (!com.bingbaihanji.bdec.ir.GenericMethodResolver.isGenericClass(
                ne.instantiatedType().internalName())) {
            return rhs;
        }
        return ne.withDiamond();
    }

    /** 表达式是否产生 boolean 值(用于 int 变量声明重标). */
    public static boolean isBooleanExpression(Expression e) {
        if (e == null) {
            return false;
        }
        if (e instanceof BinExpr bin) {
            BinaryOperator op = bin.operator();
            return op == BinaryOperator.AND || op == BinaryOperator.OR
                    || op == BinaryOperator.EQ || op == BinaryOperator.NE
                    || op == BinaryOperator.LT || op == BinaryOperator.GT
                    || op == BinaryOperator.LE || op == BinaryOperator.GE;
        }
        if (e instanceof UnExpr ue) {
            return ue.operator() == UnaryOperator.NOT;
        }
        if (e instanceof LitExpr lit) {
            return lit.value() instanceof Boolean;
        }
        if (e instanceof InvocationExpr inv) {
            return inv.returnType() != null
                    && inv.returnType().kind() == TypeKind.BOOLEAN;
        }
        // 注意:不含 VarExpr——引用 int 变量的复制会误标为 boolean.
        return false;
    }

    /** 检测复合赋值:x = x OP y → x OP= y,返回 OP 或 null. */
    public static BinaryOperator detectCompoundOp(Expression lhs, Expression rhs) {
        if (!(rhs instanceof BinExpr bin)) {
            return null;
        }
        // 匹配:lhs 与二元表达式的左操作数匹配
        if (expressionsMatch(lhs, bin.left())) {
            return bin.operator();
        }
        return null;
    }

    /** 检查两个表达式在结构上是否等价(相同的变量/字段).
     *  处理等价关系:{@code VarExpr("size") ≈ FieldAccessExpr(this, "size")},
     *  这是由于 {@code FIELD_LOAD on this} 发出了裸字段名. */
    public static boolean expressionsMatch(Expression a, Expression b) {
        if (a instanceof VarExpr va && b instanceof VarExpr vb) {
            return va.name().equals(vb.name());
        }
        if (a instanceof FieldAccessExpr fa && b instanceof FieldAccessExpr fb) {
            return fa.fieldName().equals(fb.fieldName())
                    && (fa.target() == null && fb.target() == null
                    || (fa.target() != null && fb.target() != null
                    && expressionsMatch(fa.target(), fb.target())));
        }
        // 跨类型:VarExpr("size") 匹配 FieldAccessExpr(this, "size")
        if (a instanceof VarExpr va && b instanceof FieldAccessExpr fb) {
            return fb.target() instanceof VarExpr t && "this".equals(t.name())
                    && va.name().equals(fb.fieldName());
        }
        if (b instanceof VarExpr vb && a instanceof FieldAccessExpr fa) {
            return fa.target() instanceof VarExpr t && "this".equals(t.name())
                    && vb.name().equals(fa.fieldName());
        }
        return false;
    }

    /**
     * 泛型返回强转:方法声明返回类型携带类型变量(如 V/K/List&lt;V&gt;)时,
     * 返回值 IR 类型是擦除后的 Object 或不匹配,插入 {@code (V)} 强转
     * (参照 vineflower).如 {@code return array[...]} (Object) 对
     * {@code V get()} 需 {@code (V) array[...]}.</p>
     *
     * @param retVal      返回值 AST 表达式
     * @param declaredRet 方法声明返回类型(可为 null)
     * @param valueType   返回值的 IR 类型(可为 null)
     */
    public static Expression applyGenericReturnCast(Expression retVal, JavaType declaredRet,
                                                    JavaType valueType) {
        if (retVal == null || declaredRet == null) {
            return retVal;
        }
        // 字面量 null:return null 不需要 (V) null(三元整体不是 LitExpr,仍会强转)
        if (retVal instanceof LitExpr lit && lit.value() == null) {
            return retVal;
        }
        // lambda/方法引用不能作为强转操作数((V) (t) -> t 非法)——其类型由
        // 目标类型自行推断,跳过.
        if (retVal instanceof com.bingbaihanji.bdec.ast.expr.LambdaExpr) {
            return retVal;
        }
        // 表达式已知类型已是声明返回类型(如 return this.put() 返回 V;
        // 三元任一分支返回 V,如 existing!=null ? existing : this.put(...)):
        // 无需强转——避免对已是 V 的值冗余加 (V).
        if (exprHasKnownTypeOf(retVal, declaredRet)) {
            return retVal;
        }
        JavaType target = castTargetIfNeeded(valueType, declaredRet);
        return target != null ? new com.bingbaihanji.bdec.ast.expr.CastExpr(target, retVal) : retVal;
    }

    /**
     * 表达式的已知类型是否等于目标类型(沿 CondExpr 分支递归).
     * 只信任可确定性获得类型的节点(InvocationExpr/CastExpr/NewExpr/LitExpr);
     * VarExpr/ArrayAccessExpr 类型未知,返回 false(保守,允许强转).
     */
    private static boolean exprHasKnownTypeOf(Expression e, JavaType target) {
        if (e instanceof CondExpr ce) {
            return exprHasKnownTypeOf(ce.trueExpr(), target)
                    || exprHasKnownTypeOf(ce.falseExpr(), target);
        }
        JavaType t;
        if (e instanceof InvocationExpr inv) {
            t = inv.returnType();
        } else if (e instanceof com.bingbaihanji.bdec.ast.expr.CastExpr ce) {
            t = ce.targetType();
        } else if (e instanceof NewExpr ne) {
            t = ne.instantiatedType();
        } else if (e instanceof LitExpr lit) {
            t = lit.type();
        } else {
            return false;
        }
        return t != null && sameType(t, target);
    }

    /**
     * 变量声明场景的泛型强转:声明类型为类型变量/泛型时,被存值类型为通配符
     * 或擦除 Object(不可直接赋值)则插入强转.如
     * {@code V newValue = function.apply(...)}(apply 返回 {@code ? extends V})
     * → {@code V newValue = (V) function.apply(...)}.
     */
    public static Expression applyGenericDeclCast(Expression rhs, JavaType valueType,
                                                  JavaType declType) {
        if (rhs == null || valueType == null || declType == null) {
            return rhs;
        }
        JavaType target = castTargetIfNeeded(valueType, declType);
        return target != null ? new com.bingbaihanji.bdec.ast.expr.CastExpr(target, rhs) : rhs;
    }

    /** 通配符不能作声明类型(? extends V x 非法):返回边界(V);无边界返回 Object. */
    public static JavaType wildcardBound(JavaType t) {
        if (t == null || t.kind() != TypeKind.WILDCARD) {
            return t;
        }
        return t.typeArguments().isEmpty()
                ? JavaType.classType("java/lang/Object") : t.typeArguments().getFirst();
    }

    /**
     * 值类型无法直接赋给声明/返回类型(需强转)时返回强转目标,否则 null.
     */
    private static JavaType castTargetIfNeeded(JavaType valueType, JavaType declaredType) {
        if (!isTypeVarBearing(declaredType)) {
            return null;
        }
        // 值类型已知且已是声明类型(如 this.put() 返回 V → 变量 V):无需强转
        if (valueType != null) {
            if (sameType(valueType, declaredType)) {
                return null;
            }
            // 值类型即同类型变量(如 V 经变量传递 → 声明 V):无需强转
            if (valueType.kind() == TypeKind.TYPE_VARIABLE
                    && declaredType.kind() == TypeKind.TYPE_VARIABLE
                    && valueType.internalName() != null
                    && valueType.internalName().equals(declaredType.internalName())) {
                return null;
            }
            // 值类型与声明类型是不同名类型变量(如 Function.apply(T) 的实参 V
            // 对参数 T——方法类型变量未绑定):强转 (T) v 无意义且错误,跳过.
            if (valueType.kind() == TypeKind.TYPE_VARIABLE
                    && declaredType.kind() == TypeKind.TYPE_VARIABLE) {
                return null;
            }
        }
        // 值类型未知(null,如三元/PHI 合并):保守插入——对类型变量返回强转总是
        // 安全,冗余时由 RedundantCastRewriter 按操作数类型清除.
        return castTargetFor(declaredType);
    }

    /** 类型是否携带类型变量(TYPE_VARIABLE/通配符/泛型实参含类型变量). */
    private static boolean isTypeVarBearing(JavaType t) {
        if (t == null) {
            return false;
        }
        return switch (t.kind()) {
            case TYPE_VARIABLE, WILDCARD -> true;
            case CLASS -> !t.typeArguments().isEmpty()
                    && t.typeArguments().stream().anyMatch(ExprCleanup::isTypeVarBearing);
            case ARRAY -> isTypeVarBearing(JavaType.elementOf(t));
            default -> false;
        };
    }

    /** 两个类型是否完全相同(逐层比较 kind/internalName/泛型实参). */
    private static boolean sameType(JavaType a, JavaType b) {
        if (a == null || b == null || a.kind() != b.kind()) {
            return false;
        }
        if (!java.util.Objects.equals(a.internalName(), b.internalName())) {
            return false;
        }
        if (a.typeArguments().size() != b.typeArguments().size()) {
            return false;
        }
        for (int i = 0; i < a.typeArguments().size(); i++) {
            if (!sameType(a.typeArguments().get(i), b.typeArguments().get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 强转目标:类型变量→自身;通配符→边界(? extends V→V);泛型类→擦除(List&lt;V&gt;→List);
     *  数组→自身(如 T[] → (T[]),解决 toArray 的 Object[]→T[] 返回). */
    private static JavaType castTargetFor(JavaType declaredType) {
        return switch (declaredType.kind()) {
            case TYPE_VARIABLE -> declaredType;
            case WILDCARD -> wildcardBound(declaredType);
            case ARRAY -> declaredType;
            case CLASS -> {
                if (declaredType.typeArguments().isEmpty()) {
                    yield declaredType;
                }
                String internal = declaredType.internalName();
                yield new JavaType(TypeKind.CLASS, internal, "L" + internal + ";",
                        List.of(), 0);
            }
            default -> null;
        };
    }
}
