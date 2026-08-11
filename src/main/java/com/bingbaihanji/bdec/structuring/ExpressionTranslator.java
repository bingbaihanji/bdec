package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 表达式翻译器 — 将 IR 指令/值转换为 AST 表达式节点.
 *
 * <p>从 BlockReducer 中提取,遵循 CFR 的 Op03SimpleStatement 委托模式.
 * 包含值→表达式转换、运算符检测、复合赋值简化等逻辑.
 *
 * <p>所有方法均为静态方法或接收显式参数,不依赖 BlockReducer 的内部状态.
 */
public final class ExpressionTranslator {

    // ── 语句/表达式分类 ──

    /** 检查 IR 指令是否为语句根(STORE, RETURN, THROW 等会产生副作用的指令) */
    public static boolean isStatementRoot(IrInstruction insn) {
        return switch (insn.opcode()) {
            case STORE, RETURN, THROW, FIELD_STORE, ARRAY_STORE -> true;
            case INVOKE -> insn.resultType().kind() == TypeKind.VOID
                    || insn.resultType().kind() == null;
            case INC -> true;
            case MONITOR_ENTER, MONITOR_EXIT -> true;
            default -> false;
        };
    }

    /** 判断表达式是否可忽略——仅裸变量或临时引用(作为语句无意义) */
    public static boolean isIgnorableExpr(Expression e) {
        if (e instanceof VarExpr v) {
            String name = v.name();
            return name.startsWith("var") || name.startsWith("tmp") || name.startsWith("?")
                    || "this".equals(name);
        }
        if (e instanceof LitExpr) return true;
        if (e instanceof FieldAccessExpr) return true;
        return false;
    }

    /** 判断表达式是否产生 void 类型(如 void 方法调用) */
    public static boolean isVoidExpr(Expression e) {
        return e instanceof InvocationExpr inv
                && inv.returnType() != null
                && inv.returnType().kind() == TypeKind.VOID;
    }

    /** 判断表达式是否为赋值表达式 */
    public static boolean isAssignExpr(Expression e) {
        return e instanceof AssignExpr;
    }

    /** 判断语句是否为空的代码块(无语句或仅含单个空块) */
    public static boolean isEmptyBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            return bs.statements().isEmpty()
                    || (bs.statements().size() == 1 && isEmptyBlock(bs.statements().getFirst()));
        }
        return false;
    }

    /** 检查文字是否为指定的布尔值 */
    public static boolean isBooleanLit(Expression e, boolean expected) {
        return e instanceof LitExpr lit
                && lit.value() instanceof Boolean b && b == expected;
    }

    /** 检查 Value 是否表示布尔值(布尔变量、布尔返回的调用、条件/比较/instanceof) */
    public static boolean isBooleanValue(Value v) {
        if (v instanceof Variable var) {
            return var.type().kind() == TypeKind.BOOLEAN;
        }
        if (v instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            if (def.resultType() != null
                    && def.resultType().kind() == TypeKind.BOOLEAN) {
                return true;
            }
            if (def.opcode() == IrOpcode.INVOKE && def.resultType() != null
                    && def.resultType().kind() == TypeKind.BOOLEAN) {
                return true;
            }
            return def.opcode() == IrOpcode.CONDITION
                    || def.opcode() == IrOpcode.COMPARE
                    || def.opcode() == IrOpcode.INSTANCE_OF;
        }
        return false;
    }

    /** 检查值是否为简单值——变量或常量,无需进一步展开 */
    public static boolean isSimpleValue(Value v) {
        return v instanceof Variable || v instanceof ConstantValue;
    }

    // ── 复合赋值检测 ──

    /** 检测复合赋值模式:{@code x = x OP y} → 返回 OP(表示 x OP= y) */
    public static BinaryOperator detectCompoundOp(Expression lhs, Expression rhs) {
        if (!(rhs instanceof BinExpr bin)) return null;
        if (expressionsMatch(lhs, bin.left())) return bin.operator();
        return null;
    }

    /** 检测自增/自减模式:{@code x = x + 1} → POST_INC / POST_DEC */
    public static UnaryOperator detectIncrement(BinExpr bin) {
        if (!(bin.right() instanceof LitExpr lit)) return null;
        if (!(lit.value() instanceof Number num)) return null;
        if (num.doubleValue() != 1.0) return null;
        return switch (bin.operator()) {
            case ADD -> UnaryOperator.POST_INC;
            case SUB -> UnaryOperator.POST_DEC;
            default -> null;
        };
    }

    // ── 表达式等价性检查 ──

    /** 检查两个表达式在结构上是否等价(相同的变量/字段) */
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

    /** 检查两个表达式是否等价(浅层检查,用于语句匹配) */
    public static boolean expressionsEquivalent(Expression a, Expression b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof VarExpr va && b instanceof VarExpr vb) {
            return va.name().equals(vb.name());
        }
        if (a instanceof LitExpr la && b instanceof LitExpr lb) {
            Object av = la.value(), bv = lb.value();
            return av == null ? bv == null : av.equals(bv);
        }
        return false;
    }

    // ── 语句工具 ──

    /** 递归展开语句树为扁平列表 */
    public static List<Statement> collectStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> result = new java.util.ArrayList<>();
            for (Statement c : bs.statements()) {
                result.addAll(collectStatements(c));
            }
            return result;
        }
        return s == null ? List.of() : List.of(s);
    }

    /** 检查语句中是否包含 ReturnStatement */
    public static boolean hasReturnStmt(Statement s) {
        if (s instanceof ReturnStatement) return true;
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream().anyMatch(ExpressionTranslator::hasReturnStmt);
        }
        if (s instanceof IfStatement i) {
            return hasReturnStmt(i.thenBranch())
                    || (i.elseBranch() != null && hasReturnStmt(i.elseBranch()));
        }
        return false;
    }

    /** 检查语句是否匹配候选列表中的任一语句(基于结构等价) */
    public static boolean matchesAny(Statement s, List<Statement> candidates) {
        for (Statement c : candidates) {
            if (s == c) return true;
            if (s instanceof ExpressionStatement es && c instanceof ExpressionStatement ce) {
                if (expressionsEquivalent(es.expression(), ce.expression())) return true;
            }
            if (s instanceof ReturnStatement rs && c instanceof ReturnStatement rc) {
                if (rs.value() == null && rc.value() == null) return true;
                if (rs.value() != null && rc.value() != null
                        && expressionsEquivalent(rs.value(), rc.value())) return true;
            }
        }
        return false;
    }

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
                if (val == null) yield new VarExpr("null");
                yield new LitExpr(val, cv.type());
            }
            case InstructionRef ref -> {
                IrInstruction def = ref.instruction();
                Expression expr = instrToExpr.apply(def);
                yield expr != null ? expr : new VarExpr("tmp" + def.id());
            }
            default -> new VarExpr("varUnresolved");
        };
    }

    /** 将 IR CONST 指令转为 LitExpr */
    public static Expression constToExpr(IrInstruction insn) {
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

    /** 对表达式应用布尔值注解(若存在) */
    public static Expression applyBooleanAnnotation(IrInstruction insn, Expression expr) {
        if (!insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN)) {
            return expr;
        }
        if (!insn.operands().isEmpty()
                && insn.operands().getFirst() instanceof InstructionRef ref
                && ref.instruction().opcode() == IrOpcode.PHI) {
            return expr;
        }
        var ann = insn.getAnnotation(
                com.bingbaihanji.bdec.semantic.SemanticTag.BOOLEAN_RETURN);
        if (ann == null) return expr;
        boolean boolVal = ann.getBoolean(
                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_BOOLEAN_VALUE);
        return new LitExpr(boolVal, JavaType.BOOLEAN);
    }

    // ── 名称工具 ──

    /** 将完全限定内部类名简化为短名称 */
    public static String simplifyClassName(String internalName) {
        if (internalName == null) return null;
        int slash = internalName.lastIndexOf('/');
        if (slash >= 0) return internalName.substring(slash + 1);
        int dollar = internalName.lastIndexOf('$');
        if (dollar >= 0) return internalName.substring(dollar + 1);
        return internalName;
    }

    /** 检查类型是否为 java.lang.Class 类型 */
    public static boolean isClassType(JavaType type) {
        return type != null && type.kind() == TypeKind.CLASS
                && "java/lang/Class".equals(type.internalName());
    }

    /** 从一元字节码推断 UnaryOperator */
    public static UnaryOperator inferUnaryOp(int bc) {
        return switch (bc) {
            case 0x74, 0x78 -> UnaryOperator.NEG;   // INEG / LNEG
            case 0x76, 0x7a -> UnaryOperator.NEG;   // FNEG / DNEG
            default -> null;
        };
    }
}
