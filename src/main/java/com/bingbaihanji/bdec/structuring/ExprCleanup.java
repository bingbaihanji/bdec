package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.AnnotationRenderer;
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
}
