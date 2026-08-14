package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.Map;
import java.util.function.Function;

/**
 * 表达式翻译器 — 将 IR 指令/值转换为 AST 表达式节点.
 *
 * <p>从 BlockReducer 中提取,遵循 CFR 的 Op03SimpleStatement 委托模式.
 * 包含值→表达式转换逻辑.
 *
 * <p>所有方法均为静态方法或接收显式参数,不依赖 BlockReducer 的内部状态.
 */
public final class ExpressionTranslator {

    // ── 值→表达式转换 ──

    /** 将 Value(Variable / ConstantValue / InstructionRef)转为 Expression */
    public static Expression valueToExpr(Value v,
                                         Map<Variable, Value> varStoreSource,
                                         Function<IrInstruction, Expression> instrToExpr) {
        return switch (v) {
            case Variable var -> {
                Value storeSource = varStoreSource.get(var);
                if (storeSource != null) {
                    yield valueToExpr(storeSource, varStoreSource, instrToExpr);
                }
                yield varToExpr(var, false);
            }
            case ConstantValue cv -> {
                Object val = cv.value();
                if (val == null) {
                    yield new VarExpr("null");
                }
                yield new LitExpr(val, cv.type());
            }
            case com.bingbaihanji.bdec.ir.DynamicConstantValue dcv -> dynamicConstToExpr(dcv);
            case InstructionRef ref -> {
                IrInstruction def = ref.instruction();
                Expression expr = instrToExpr.apply(def);
                yield expr != null ? expr : new VarExpr("tmp" + def.id());
            }
            default -> new VarExpr("varUnresolved");
        };
    }

    /** 将动态常量(condy)转为 AST 表达式 */
    public static Expression dynamicConstToExpr(
            com.bingbaihanji.bdec.ir.DynamicConstantValue dcv) {
        return switch (dcv.kind()) {
            case NULL_CONSTANT -> new VarExpr("null");
            case CLASS_LITERAL -> new FieldAccessExpr(new VarExpr(dcv.owner()), "class");
            case QUALIFIED_REF -> new FieldAccessExpr(new VarExpr(dcv.owner()), dcv.member());
            case LITERAL -> new LitExpr(dcv.literal(), dcv.type());
            // 未知引导方法(invoke 等惰性求值常量):类型默认值兜底
            case FALLBACK -> switch (dcv.type().kind()) {
                case BOOLEAN -> new LitExpr(false, JavaType.BOOLEAN);
                case LONG -> new LitExpr(0L, JavaType.LONG);
                case FLOAT -> new LitExpr(0.0f, JavaType.FLOAT);
                case DOUBLE -> new LitExpr(0.0d, JavaType.DOUBLE);
                case INT, BYTE, SHORT, CHAR -> new LitExpr(0, dcv.type());
                default -> new VarExpr("null");
            };
        };
    }

    /** 将 IR CONST 指令转为 LitExpr */
    public static Expression constToExpr(IrInstruction insn) {
        if (!insn.operands().isEmpty()
                && insn.operands().getFirst() instanceof com.bingbaihanji.bdec.ir.DynamicConstantValue dcv) {
            return dynamicConstToExpr(dcv);
        }
        if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof ConstantValue(
                Object v, JavaType type
        )) {
            if (v instanceof String s && isClassType(type)) {
                String simpleName = simplifyClassName(s);
                return new FieldAccessExpr(new VarExpr(simpleName), "class");
            }
            if (v instanceof String s) {
                return new LitExpr(s, JavaType.classType("java/lang/String"));
            }
            if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN)) {
                var ann = insn.getAnnotation(
                        com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN);
                if (ann != null) {
                    return new LitExpr(ann.getBoolean(
                            com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_BOOLEAN_VALUE),
                            JavaType.BOOLEAN);
                }
            }
            return new LitExpr(v, type);
        }
        return new VarExpr("/* const */");
    }

    /** 将 Variable 转为相应的 VarExpr */
    public static VarExpr varToExpr(Variable var, boolean isInstanceMethod) {
        String name = var.name();
        if (isInstanceMethod && var.slot() == 0 && var.version() == 0) {
            return new VarExpr("this");
        }
        if (name != null && !name.startsWith("var")) {
            return new VarExpr(name);
        }
        if (var.version() > 0) {
            return new VarExpr("var" + var.slot());
        }
        return new VarExpr(name != null ? name : "var" + var.slot());
    }

    // ── 名称工具 ──

    /** 将完全限定内部类名简化为短名称 */
    public static String simplifyClassName(String internalName) {
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
    public static boolean isClassType(JavaType type) {
        return type != null && type.kind() == TypeKind.CLASS
                && "java/lang/Class".equals(type.internalName());
    }
}
