package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.type.TypeResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 语句级工具方法集合(从 BlockReducer 抽取).
 *
 * <p>本类集中存放无状态,与归约上下文无关的纯函数:
 * 表达式/语句形状判断,后置自增折叠,循环声明提升,
 * 指令分类与布尔值判定等. 名称识别,结构比较,return 归一化
 * 已分别拆至 {@link NameUtils}/{@link ComparisonUtils}/{@link ReturnNormalizer}.</p>
 */
final class StatementUtils {

    private StatementUtils() {}

    /** 判断指令是否产生副作用,从而应成为一条语句 */
    static boolean isStatementRoot(IrInstruction insn) {
        return switch (insn.opcode()) {
            case STORE, RETURN, THROW, FIELD_STORE, ARRAY_STORE -> true;
            case INVOKE -> insn.resultType().kind() == TypeKind.VOID
                    || insn.resultType().kind() == null; // void 类型调用
            case INC -> true; // IINC 总以语句形式出现
            case MONITOR_ENTER, MONITOR_EXIT -> true;
            default -> false;
        };
    }

    /** 判断表达式是否为后置自增/自减表达式 */
    static boolean isPostIncDec(Expression e) {
        return e instanceof UnExpr u
                && (u.operator() == UnaryOperator.POST_INC
                || u.operator() == UnaryOperator.POST_DEC);
    }

    /** 将后置自增/自减折叠进语句中对该变量的首个引用.
     *  例:println(v) + v++ → println(v++).不匹配时返回 null. */
    static Statement foldPostInc(Statement s, String varName, UnaryOperator op) {
        if (s instanceof ExpressionStatement es) {
            Expression folded = foldPostIncInExpr(es.expression(), varName, op);
            if (folded != es.expression()) {
                return new ExpressionStatement(folded);
            }
        } else if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement rs
                && rs.value() != null) {
            // 后置自增折叠进 return 值(如 return array[nextIndex++] 的
            // nextIndex 自增若独立成句,数组访问会读到自增后的值,错位一跳).
            Expression folded = foldPostIncInExpr(rs.value(), varName, op);
            if (folded != rs.value()) {
                return new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(folded);
            }
        }
        return null;
    }

    /** 递归在表达式中查找变量引用并替换为后置自增/自减表达式 */
    static Expression foldPostIncInExpr(Expression e, String varName, UnaryOperator op) {
        if (e == null) {
            return null;
        }
        if (e instanceof VarExpr v && varName.equals(v.name())) {
            return new UnExpr(op, v);
        }
        if (e instanceof BinExpr b) {
            Expression l = foldPostIncInExpr(b.left(), varName, op);
            if (l != b.left()) {
                return new BinExpr(b.operator(), l, b.right());
            }
            Expression r = foldPostIncInExpr(b.right(), varName, op);
            if (r != b.right()) {
                return new BinExpr(b.operator(), b.left(), r);
            }
        } else if (e instanceof InvocationExpr inv) {
            List<Expression> newArgs = new ArrayList<>();
            boolean changed = false;
            for (Expression a : inv.arguments()) {
                Expression fa = foldPostIncInExpr(a, varName, op);
                if (fa != a) {
                    changed = true;
                }
                newArgs.add(fa != null ? fa : a);
            }
            if (changed) {
                return new InvocationExpr(inv.target(), inv.methodName(), newArgs, inv.returnType());
            }
        } else if (e instanceof FieldAccessExpr fa && fa.target() != null) {
            Expression t = foldPostIncInExpr(fa.target(), varName, op);
            if (t != fa.target()) {
                return new FieldAccessExpr(t, fa.fieldName());
            }
        } else if (e instanceof AssignExpr a) {
            Expression v2 = foldPostIncInExpr(a.value(), varName, op);
            if (v2 != a.value()) {
                // 保留复合赋值运算符:s += j 折叠 j++ 必须得到 s += j++,
                // 若丢弃 compoundOp 会变成 s = j++(丢失累加的左操作数,语义错误).
                return new AssignExpr(a.target(), v2, a.compoundOp());
            }
        } else if (e instanceof com.bingbaihanji.bdec.ast.expr.CastExpr ce) {
            // return (E) array[nextIndex++] 的数组下标在强转操作数内
            Expression o = foldPostIncInExpr(ce.operand(), varName, op);
            if (o != ce.operand()) {
                return new com.bingbaihanji.bdec.ast.expr.CastExpr(
                        ce.targetType(), o, ce.typeAnnotations());
            }
        } else if (e instanceof com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr aa) {
            Expression arr = foldPostIncInExpr(aa.array(), varName, op);
            if (arr != aa.array()) {
                return new com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr(arr, aa.index());
            }
            Expression idx = foldPostIncInExpr(aa.index(), varName, op);
            if (idx != aa.index()) {
                return new com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr(aa.array(), idx);
            }
        } else if (e instanceof UnExpr u && u.operator() != UnaryOperator.POST_INC
                && u.operator() != UnaryOperator.POST_DEC) {
            Expression o = foldPostIncInExpr(u.operand(), varName, op);
            if (o != u.operand()) {
                return new UnExpr(u.operator(), o);
            }
        }
        return e;
    }

    /** 判断表达式是否可忽略——仅裸变量或临时引用 */
    static boolean isIgnorableExpr(Expression e) {
        if (e instanceof VarExpr v) {
            String name = v.name();
            return name.startsWith("var") || name.startsWith("tmp") || name.startsWith("?")
                    || "this".equals(name) || "switchKey".equals(name);
        }
        // 跳过独立的字面量表达式——字符 串,数字,布尔值本身不是合法的独立语句
        if (e instanceof LitExpr) {
            return true;
        }
        // 独立的字段访问(例如 GETSTATIC 用于方法引用时的 System.out)不是合法的 Java 语句
        if (e instanceof FieldAccessExpr) {
            return true;
        }
        // 独立的数组访问(arr[i])——纯读,无副作用,丢弃(如 hash 方法中残存的 hashes[i];)
        if (e instanceof ArrayAccessExpr) {
            return true;
        }
        // 独立的数组分配(new int[n])——分配后即弃,无构造器副作用;对象 new 保留
        //(构造器可能有副作用,如 new FileInputStream("x")).
        if (e instanceof NewExpr ne && ne.instantiatedType() != null
                && ne.instantiatedType().kind() == TypeKind.ARRAY) {
            return true;
        }
        return false;
    }

    /** 判断表达式是否产生 void 类型(如 void 方法调用) */
    static boolean isVoidExpr(Expression e) {
        return e instanceof InvocationExpr inv
                && inv.returnType() != null
                && inv.returnType().kind() == TypeKind.VOID;
    }

    /** 判断表达式是否为(复合)赋值表达式.
     *  赋值表达式不能安全地提升为 return 表达式——其类型可能与方法返回类型不匹配. */
    static boolean isAssignExpr(Expression e) {
        return e instanceof com.bingbaihanji.bdec.ast.expr.AssignExpr
                || e instanceof com.bingbaihanji.bdec.ast.expr.UnExpr
                && (((com.bingbaihanji.bdec.ast.expr.UnExpr) e).operator()
                == com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC
                || ((com.bingbaihanji.bdec.ast.expr.UnExpr) e).operator()
                == com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC);
    }

    /** 判断语句块是否为空或仅包含空块 */
    static boolean isEmptyBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            return bs.statements().isEmpty()
                    || bs.statements().stream().allMatch(StatementUtils::isEmptyBlock);
        }
        return false;
    }

    /**
     * 提升 do-while 循环体中被条件引用的前导变量声明到循环外.
     *
     * <p>循环折叠把预置头块(如 {@code int i = 0;} 的 STORE)并入循环体.
     * do-while 的初始化声明必须位于循环外:声明在体内会与外部同名变量
     * 冲突(JLS 禁止局部变量遮蔽),且每次迭代重置初始值会改变语义.
     * 仅提升条件引用的声明——真正的体内局部声明
     * (如 {@code do { int x = f(); ... } while})不提升.</p>
     *
     * @param body 翻译后的循环体
     * @param cond 循环条件(已简化,可为 null)
     * @return [前导声明语句(可 null), 剩余循环体],无可提升时返回 null
     */
    static Statement[] hoistConditionReferencedDeclarations(Statement body, Expression cond) {
        if (body == null) {
            return null;
        }
        Set<String> condVars = new HashSet<>();
        collectVarNamesInExpr(cond, condVars);
        if (condVars.isEmpty()) {
            return null;
        }
        List<Statement> stmts = body instanceof BlockStatement bs
                ? new ArrayList<>(bs.statements()) : new ArrayList<>(List.of(body));
        List<Statement> pre = new ArrayList<>();
        while (!stmts.isEmpty()) {
            if (stmts.getFirst() instanceof VariableDeclaration vd
                    && condVars.contains(vd.name())) {
                pre.add(stmts.removeFirst());
            } else {
                break;
            }
        }
        if (pre.isEmpty()) {
            return null;
        }
        return new Statement[]{new BlockStatement(pre), new BlockStatement(stmts)};
    }

    /** 递归收集表达式中引用的变量名(仅 VarExpr). */
    private static void collectVarNamesInExpr(Expression e, Set<String> out) {
        if (e == null) {
            return;
        }
        if (e instanceof VarExpr v) {
            out.add(v.name());
            return;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.BinExpr b) {
            collectVarNamesInExpr(b.left(), out);
            collectVarNamesInExpr(b.right(), out);
        } else if (e instanceof com.bingbaihanji.bdec.ast.expr.UnExpr u) {
            collectVarNamesInExpr(u.operand(), out);
        } else if (e instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            collectVarNamesInExpr(inv.target(), out);
            for (Expression a : inv.arguments()) {
                collectVarNamesInExpr(a, out);
            }
        } else if (e instanceof com.bingbaihanji.bdec.ast.expr.FieldAccessExpr fa) {
            collectVarNamesInExpr(fa.target(), out);
        } else if (e instanceof com.bingbaihanji.bdec.ast.expr.AssignExpr a) {
            collectVarNamesInExpr(a.target(), out);
            collectVarNamesInExpr(a.value(), out);
        }
    }

    /** 判断表达式是否为指定值的布尔字面量 */
    static boolean isBooleanLit(Expression e, boolean expected) {
        if (e instanceof LitExpr lit) {
            Object v = lit.value();
            return v instanceof Boolean b && b == expected;
        }
        return false;
    }

    /** 检测自增/自减模式:x = x + 1 → x++, x = x - 1 → x-- */
    static UnaryOperator detectIncrement(BinExpr bin) {
        boolean isOne = bin.right() instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                && lr.value() instanceof Integer i && i == 1;
        if (!isOne) {
            return null;
        }
        if (bin.operator() == BinaryOperator.ADD) {
            return com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC;
        }
        if (bin.operator() == BinaryOperator.SUB) {
            return com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC;
        }
        return null;
    }

    /** 递归收集所有语句,展开嵌套的 BlockStatement */
    static List<Statement> collectStatements(Statement s) {
        List<Statement> result = new ArrayList<>();
        if (s instanceof BlockStatement bs) {
            for (Statement child : bs.statements()) {
                result.addAll(collectStatements(child));
            }
        } else {
            result.add(s);
        }
        return result;
    }

    /** 去除内部类/局部类/匿名类构造函数的隐式外围实例(this)参数.
     *  在 Java 源码中,{@code new InnerClass()} 不需要显式传递 this,
     *  而字节码中内部类构造函数会包含外围实例作为第一个参数. */
    static List<Expression> stripEnclosingThis(JavaType targetType, List<Expression> args) {
        if (args.isEmpty()) {
            return args;
        }
        // 仅对内部类/局部类/匿名类(名称含 $)进行处理
        String internal = targetType.internalName();
        if (internal == null) {
            return args;
        }
        int lastSlash = internal.lastIndexOf('/');
        String simple = lastSlash >= 0 ? internal.substring(lastSlash + 1) : internal;
        if (!simple.contains("$")) {
            return args; // 非内部类,不处理
        }
        // 第一个参数若是 this 引用,则是隐含的外围实例,予以去除
        Expression first = args.getFirst();
        if (first instanceof VarExpr v && "this".equals(v.name())) {
            List<Expression> filtered = new ArrayList<>(args);
            filtered.removeFirst();
            return filtered;
        }
        return args;
    }

    /** 检查指令列表中是否包含 NEW 指令(对象创建).
     *  用于区分 catch 子句(创建新异常)与 finally 块(重新抛出原始异常). */
    static boolean containsNewInstruction(List<IrInstruction> insns) {
        for (IrInstruction i : insns) {
            if (i.opcode() == IrOpcode.NEW) {
                return true;
            }
        }
        return false;
    }

    /** 检查处理器基本块中是否包含 NEW 指令(用于区分 catch 与 finally). */
    static boolean handlerBlockContainsNew(BasicBlock handlerBlock, LinearIr ir) {
        if (handlerBlock == null || ir == null) {
            return false;
        }
        for (IrInstruction i : ir.instructionsOf(handlerBlock)) {
            if (i.opcode() == IrOpcode.NEW) {
                return true;
            }
        }
        return false;
    }

    /** 判断值是否简单到可以内联(常量或基本表达式) */
    static boolean isSimpleValue(Value v) {
        if (v instanceof ConstantValue) {
            return true;
        }
        if (v instanceof InstructionRef ref) {
            IrOpcode op = ref.instruction().opcode();
            return op == IrOpcode.CONST || op == IrOpcode.LOAD || op == IrOpcode.CAST
                    || op == IrOpcode.FIELD_LOAD || op == IrOpcode.ARRAY_LENGTH
                    || op == IrOpcode.INSTANCE_OF;
        }
        return false;
    }

    /** 判断 Value 是否表示布尔值(变量,方法返回值等).
     *  用于生成正确的 {@code if(flag)} / {@code if(!flag)} 语句,
     *  而不是 {@code if(flag != 0)}——后者对布尔表达式会产生类型不匹配错误. */
    static boolean isBooleanValue(Value v) {
        if (v instanceof Variable var) {
            return var.type().kind() == TypeKind.BOOLEAN;
        }
        if (v instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            // 检查定义指令是否产生布尔类型结果
            if (def.resultType() != null
                    && def.resultType().kind() == TypeKind.BOOLEAN) {
                return true;
            }
            // 返回布尔值的 INVOKE 指令
            if (def.opcode() == IrOpcode.INVOKE && def.resultType() != null
                    && def.resultType().kind() == TypeKind.BOOLEAN) {
                return true;
            }
            // CONDITION,COMPARE,INSTANCE_OF 产生布尔值
            // INSTANCE_OF 在 JVM 字节码层面产生 int(0/1),但在 Java 源码中
            // 总是用作布尔条件,因此应简化 CONDITION 中的 "==0"/"!=0" 比较
            return def.opcode() == IrOpcode.CONDITION
                    || def.opcode() == IrOpcode.COMPARE
                    || def.opcode() == IrOpcode.INSTANCE_OF;
        }
        return false;
    }

    /** 调试用语句描述 */
    static String describeStmt(Statement s) {
        if (s instanceof BlockStatement bs) {
            return "{" + bs.statements().stream().map(StatementUtils::describeStmt)
                    .reduce((a, b) -> a + "; " + b).orElse("") + "}";
        }
        if (s instanceof IfStatement i) {
            return "if(" + i.condition() + ")" + describeStmt(i.thenBranch());
        }
        if (s instanceof ExpressionStatement es) {
            return es.expression().toString();
        }
        return s.getClass().getSimpleName();
    }

    /** continue 语句 */
    static Statement continueStmt() {
        return new com.bingbaihanji.bdec.ast.stmt.ContinueStatement();
    }

    /** 语句列表折叠为单语句或块 */
    static Statement blockOf(List<Statement> stmts) {
        if (stmts.isEmpty()) {
            return new BlockStatement(List.of());
        }
        if (stmts.size() == 1) {
            return stmts.getFirst();
        }
        return new BlockStatement(stmts);
    }

    /** 将字节码操作码映射为 UNARY IR 指令的一元运算符 */
    static UnaryOperator inferUnaryOp(int bc) {
        return switch (bc) {
            case 0x74, 0x75, 0x76, 0x77 -> UnaryOperator.NEG; // INEG, LNEG, FNEG, DNEG
            default -> UnaryOperator.NEG;
        };
    }

    /**
     * 从实现方法描述符构建带类型的参数占位符.
     * 用于无捕获变量的 lambda,此时 INDY 操作数为空,
     * 无法从操作数推导参数类型.此方法从实现方法描述符(如 "(II)I")
     * 中解析出参数类型,为每个参数创建带类型和通用名称的占位符.
     *
     * @param methodDescriptor 实现方法的 JVM 描述符(如 "(II)I")
     * @return 参数列表,仅含类型和通用名称(arg0,arg1,...)
     */
    static List<LambdaExpr.Param> buildParamsFromDescriptor(String methodDescriptor) {
        List<LambdaExpr.Param> params = new ArrayList<>();
        JavaType[] paramTypes = TypeResolver.parseMethodParameterTypes(methodDescriptor);
        for (int i = 0; i < paramTypes.length; i++) {
            params.add(new LambdaExpr.Param("arg" + i, paramTypes[i]));
        }
        return params;
    }
}
