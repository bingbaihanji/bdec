package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InstanceOfExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.NewExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令 → AST 表达式/语句翻译器(从 BlockReducer 拆分).
 *
 * <p>把单条 {@link IrInstruction} 翻译为 AST:副作用指令(STORE/RETURN/THROW/
 * INVOKE 等)成为语句,中间值指令(LOAD/CONST/BINARY/CAST 等)经
 * {@code InstructionRef} 链解析为表达式树.依赖的归约状态(表达式翻译,
 * 作用域追踪,PHI 分支上下文,NEW 合并等)全部经 {@link ReducerOps} 回调
 * {@link BlockReducer},翻译逻辑与归约状态解耦.</p>
 */
public final class ExprTranslator {

    /** 归约器回调(BlockReducer 实现). */
    private final ReducerOps ops;

    public ExprTranslator(ReducerOps ops) {
        this.ops = ops;
    }

    /**
     * 将单条 IR 指令翻译为 AST 语句.
     *
     * <ul>
     *   <li>MONITOR_ENTER/EXIT 由 SynchronizedRecognizer → SynchronizedStatement 处理</li>
     *   <li>RETURN:对 <clinit> 抑制 "return;"(JVM 伪影)</li>
     *   <li>THROW:必要时引入临时变量声明</li>
     *   <li>STORE:首个定义版本 emit "Type name = value;",后续转为赋值</li>
     * </ul>
     */
    public Statement translateStmt(IrInstruction insn) {
        return switch (insn.opcode()) {
            // MONITOR_ENTER/EXIT 由 SynchronizedRecognizer → SynchronizedStatement 处理.
            // 如果在此处出现未处理的实例,跳过它们而非生成非法语法.
            case MONITOR_ENTER, MONITOR_EXIT -> null;
            case RETURN -> {
                if (insn.operands().isEmpty()) {
                    // 静态初始化器(<clinit>)不应输出 "return;" ——
                    // 它只是 JVM 伪影,不是合法的 Java 源码.
                    if (ops.currentIr() != null && "<clinit>".equals(ops.currentIr().method().name())) {
                        yield null;
                    }
                    yield new ReturnStatement(null);
                } else {
                    Expression retVal = ops.valueToExpr(insn.operands().getFirst());
                    // 目标类型绑定的菱形推断(return 场景):方法返回类型带泛型实参
                    // 时置菱形标志(如 return new HashMap<>();)
                    retVal = ExprCleanup.markTargetDiamond(retVal, ops.genericMethodReturnType());
                    // 应用语义注解中的布尔折叠
                    retVal = AstCleanup.applyBooleanAnnotation(insn, retVal);
                    // 对 boolean 返回方法,将整数字面量转为布尔值
                    //(PHI 解析后的值可能跳过了注解)
                    if (ops.currentMethodReturnsBoolean()
                            && retVal instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lit
                            && lit.value() instanceof Integer i) {
                        retVal = new com.bingbaihanji.bdec.ast.expr.LitExpr(
                                i != 0, JavaType.BOOLEAN);
                    }
                    yield new ReturnStatement(retVal);
                }
            }
            case THROW -> {
                Expression thrown = translateExpr(insn);
                if (thrown instanceof VarExpr v && v.name().startsWith("var")
                        && ops.tryDeclareVar("$exc$" + v.name())) {
                    yield new com.bingbaihanji.bdec.ast.stmt.BlockStatement(List.of(
                            new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                                    JavaType.classType("java/lang/Throwable"),
                                    v.name(), null),
                            new ThrowStatement(thrown)));
                }
                yield new ThrowStatement(thrown);
            }
            case STORE -> {
                // 合成异常占位符(slot < 0):handler 入口由 JVM 隐式压栈的
                // 异常对象. catch 子句的变量已由 catch 声明绑定,
                // 此 STORE 不产生语句.
                if (insn.operands().size() >= 2
                        && insn.operands().get(1) instanceof Variable sv && sv.slot() < 0) {
                    yield null;
                }
                // 对每个逻辑变量的首次存储产生 "Type name = value;".
                // 同时使用 slot+version:任意 slot 上的 version 1 总是首个局部变量定义
                //(version 0 = 参数).同时使用按作用域的追踪,
                // 使相同 slot 上不同分支体的临时变量各自获得独立的声明.
                Value target = insn.operands().getFirst();
                // 排除参数(已在方法签名中声明)与 this 接收者.
                // 注意不能用 slot() != 0 排除 this:静态方法里首个局部变量
                // 同样落在 slot 0,会被误跳过声明,导致 SourceCleanup 用
                // "int var = 0" 兜底(类型推断失效的根因).
                if (target instanceof Variable v && !v.isParameter()
                        && !"this".equals(v.name())) {
                    String declName = v.name();
                    // version 1 = 此 slot 的首个局部变量 → 始终声明.
                    // version 2+ = 重新赋值 → 仅在新作用域中声明.
                    // 始终调用 tryDeclareVar 以在作用域中追踪该变量名.
                    boolean isFirstDef = v.version() == 1
                            || ops.tryDeclareVar(declName);
                    if (v.version() == 1) {
                        ops.tryDeclareVar(declName); // 在作用域中追踪
                    }
                    if (isFirstDef) {
                        Value source = insn.operands().size() > 1
                                ? insn.operands().get(1) : null;
                        Expression rhs = source != null
                                ? ops.valueToExpr(source) : null;
                        // 声明类型:JVM 将 boolean 存为 int 0/1,变量类型可能是 INT;
                        // 若初始化式为布尔表达式(如 boolean r = a && b 的短路合并),
                        // 声明须重标为 boolean,否则 "int r = a && b" 无法编译.
                        JavaType declType = v.genericType() != null
                                ? v.genericType() : v.type();
                        if (declType.kind() == TypeKind.INT && ExprCleanup.isBooleanExpression(rhs)) {
                            declType = JavaType.BOOLEAN;
                        }
                        // 局部变量上的 JSR-308 类型注解(0x40):渲染后按
                        // 类型路径分组附加到声明(如 "@A String x")
                        yield new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(
                                declType, declName, ExprCleanup.markTargetDiamond(rhs, declType),
                                ExprCleanup.renderVarTypeAnnotations(v));
                    }
                }
                Expression e = translateExpr(insn);
                yield e != null ? new ExpressionStatement(e) : null;
            }
            default -> {
                Expression e = translateExpr(insn);
                yield e != null ? new ExpressionStatement(e) : null;
            }
        };
    }

    /**
     * 将单条 IR 指令翻译为 AST 表达式.
     *
     * <p>涵盖所有 IR 操作码类型的表达式翻译:CONST,LOAD,STORE(→赋值),
     * FIELD_LOAD,FIELD_STORE(→字段赋值),BINARY,COMPARE,CONDITION,
     * UNARY,INVOKE(含 INDY 的 lambda 翻译),CAST,NEW,NEW_ARRAY,
     * INSTANCE_OF,ARRAY_LOAD,ARRAY_STORE,ARRAY_LENGTH,INC,THROW,PHI 等.
     */
    public Expression translateExpr(IrInstruction insn) {
        return switch (insn.opcode()) {

            // 常量
            case CONST -> ops.constToExpr(insn);

            // 变量加载
            case LOAD -> {
                if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof Variable v) {
                    yield ops.varToExpr(v);
                }
                yield new VarExpr("var");
            }

            // 变量存储 → 赋值
            case STORE -> {
                Value target = insn.operands().getFirst();
                Value source = insn.operands().size() > 1 ? insn.operands().get(1) : null;
                Expression lhs;
                if (target instanceof Variable v) {
                    lhs = ops.varToExpr(v);
                } else {
                    lhs = ops.valueToExpr(target);
                }
                Expression rhs = source != null ? ops.valueToExpr(source) : new VarExpr("varUnresolved");
                // 复合赋值检测:x = x OP y → x OP= y
                // 检测到时仅使用右操作数(剥离重复的左操作数)
                BinaryOperator compoundOp = ExprCleanup.detectCompoundOp(lhs, rhs);
                Expression assignRhs = rhs;
                if (compoundOp != null && rhs instanceof BinExpr bin) {
                    assignRhs = bin.right(); // 剥离重复的左操作数
                }
                // x += 1 → x++  /  x -= 1 → x--
                if (compoundOp != null && assignRhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                        && lr.value() instanceof Integer i && i == 1) {
                    if (compoundOp == BinaryOperator.ADD) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, lhs);
                    } else if (compoundOp == BinaryOperator.SUB) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, lhs);
                    }
                }
                // 自增/自减检测:x = x + 1 → x++, x = x - 1 → x--
                if (compoundOp == null && rhs instanceof BinExpr bin
                        && ExprCleanup.expressionsMatch(lhs, bin.left())) {
                    UnaryOperator incOp = StatementUtils.detectIncrement(bin);
                    if (incOp != null) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(incOp, lhs);
                    }
                }
                yield new AssignExpr(lhs, assignRhs, compoundOp);
            }

            // 字段加载——在隐式 'this' 上仅使用字段名,
            // 除非存在同名的局部变量造成歧义.
            // 对于带有 DECLARING_CLASS 标记的静态字段,输出 ClassName.fieldName.
            case FIELD_LOAD -> {
                Expression obj = insn.operands().isEmpty() ? null : ops.valueToExpr(insn.operands().getFirst());
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // 检查是否有声明类注解的静态字段
                if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                    var dcAnn = insn.getAnnotation(
                            com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS);
                    if (dcAnn != null) {
                        String dc = dcAnn.getString(
                                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                        if (dc != null) {
                            int lastSlash = dc.lastIndexOf('/');
                            String simple = lastSlash >= 0
                                    ? dc.substring(lastSlash + 1) : dc;
                            // 内部类/内部枚举(如 EnumSwitchCheck$Color 的静态字段 RED):
                            // 源码中用其简单名引用(Color.RED),不能保留 $ 二进名.
                            int lastDollar = simple.lastIndexOf('$');
                            obj = new VarExpr(lastDollar >= 0
                                    ? simple.substring(lastDollar + 1) : simple);
                        }
                    }
                }
                // 在实例方法中,对 'this' 的字段加载 → 仅使用字段名,
                // 除非局部变量与该字段名冲突(如 "lock = this.lock")
                if (ops.isInstanceMethod() && obj instanceof VarExpr v && "this".equals(v.name())) {
                    if (!ops.localVarShadowsField(fName)) {
                        yield new VarExpr(fName);
                    }
                }
                yield new FieldAccessExpr(obj, fName);
            }

            // 字段存储 → 字段赋值
            case FIELD_STORE -> {
                // 操作数布局:静态字段(PUTSTATIC)仅 [value],实例字段(PUTFIELD)为 [obj, value].
                // 值始终是最后一个操作数;对象仅实例字段存在.按布局取,避免静态字段被
                // 误当成 obj,值被解析为 null 而回退 varUnresolved.
                Value val = !insn.operands().isEmpty() ? insn.operands().getLast() : null;
                Value obj = insn.operands().size() > 1 ? insn.operands().getFirst() : null;
                String fName = insn.nameHint() != null ? insn.nameHint() : "field";
                // 始终使用 this.fieldName 进行实例字段存储,
                // 使输出能够清晰区分字段赋值和局部变量赋值.
                // 防止将 "this.capacity = x" 错误输出为 "capacity = x".
                Expression lhs;
                if (ops.isInstanceMethod() && obj instanceof Variable v && v.slot() == 0) {
                    lhs = new FieldAccessExpr(new VarExpr("this"), fName);
                } else if (obj instanceof Variable v) {
                    lhs = new FieldAccessExpr(ops.varToExpr(v), fName);
                } else {
                    lhs = new FieldAccessExpr(null, fName);
                }
                Expression rhs = val != null ? ops.valueToExpr(val) : new VarExpr("varUnresolved");
                // 字段存储同样应用复合赋值和自增/自减检测
                BinaryOperator compoundOp = ExprCleanup.detectCompoundOp(lhs, rhs);
                Expression assignRhs = rhs;
                if (compoundOp != null && rhs instanceof BinExpr bin) {
                    assignRhs = bin.right();
                }
                // += 1 → ++, -= 1 → --
                if (compoundOp != null && assignRhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                        && lr.value() instanceof Integer i && i == 1) {
                    if (compoundOp == BinaryOperator.ADD) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, lhs);
                    } else if (compoundOp == BinaryOperator.SUB) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, lhs);
                    }
                }
                if (compoundOp == null && rhs instanceof BinExpr bin
                        && ExprCleanup.expressionsMatch(lhs, bin.left())) {
                    UnaryOperator incOp = StatementUtils.detectIncrement(bin);
                    if (incOp != null) {
                        yield new com.bingbaihanji.bdec.ast.expr.UnExpr(incOp, lhs);
                    }
                }
                yield new AssignExpr(lhs, assignRhs, compoundOp);
            }

            // 二元运算——使用原始字节码操作码推断运算符
            case BINARY -> {
                if (insn.operands().size() >= 2) {
                    Expression left = ops.valueToExpr(insn.operands().get(0));
                    Expression right = ops.valueToExpr(insn.operands().get(1));
                    BinaryOperator binOp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());
                    yield new BinExpr(binOp != null ? binOp : BinaryOperator.ADD, left, right);
                }
                yield new VarExpr("/* binary */");
            }

            // 比较运算
            case COMPARE -> {
                if (insn.operands().size() >= 2) {
                    Expression left = ops.valueToExpr(insn.operands().get(0));
                    Expression right = ops.valueToExpr(insn.operands().get(1));
                    yield new BinExpr(BinaryOperator.EQ, left, right);
                }
                yield new VarExpr("/* compare */");
            }

            // 条件——使用原始字节码操作码推断比较运算符
            case CONDITION -> {
                if (insn.operands().size() >= 2) {
                    Value leftOp = insn.operands().get(0);
                    Value rightOp = insn.operands().get(1);

                    // 检测布尔变量与 0 的比较:
                    //   boolean == 0 → !boolean,  boolean != 0 → boolean
                    // 此处理用于将 IFEQ/IFNE 字节码转为 if(flag) / if(!flag).
                    // 同时处理返回布尔的函数调用(desiredAssertionStatus 等).
                    // 短路合并(boolean r = a && b)的 r 在字节码中存为 int 0/1,
                    // 但其定义 STORE←PHI 已被折叠为布尔表达式——同样按布尔处理.
                    boolean leftIsBool = StatementUtils.isBooleanValue(leftOp)
                            || ops.isBooleanPhiReplacedVariable(leftOp);
                    boolean rightIsBool = StatementUtils.isBooleanValue(rightOp)
                            || ops.isBooleanPhiReplacedVariable(rightOp);
                    boolean rightIsZero = rightOp instanceof ConstantValue cv
                            && cv.value() instanceof Integer i && i == 0;
                    boolean leftIsZero = leftOp instanceof ConstantValue cv
                            && cv.value() instanceof Integer i && i == 0;

                    BinaryOperator cmpOp = IrInstruction.binaryOpFromBytecode(insn.originalOpcode());

                    // 布尔+0 简化:仅当布尔值不来自 COMPARE 时适用.
                    // COMPARE+CONDITION 需要保留原始比较运算符(如 GT),
                    // 由下方的 COMPARE 合并逻辑处理.
                    boolean leftFromCompare = leftOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE;
                    boolean rightFromCompare = rightOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE;

                    if (leftIsBool && rightIsZero && !leftFromCompare) {
                        Expression varExpr = ops.valueToExpr(leftOp);
                        if (cmpOp == BinaryOperator.EQ) {
                            yield new UnExpr(UnaryOperator.NOT, varExpr);
                        } else if (cmpOp == BinaryOperator.NE) {
                            yield varExpr;
                        }
                    }
                    if (rightIsBool && leftIsZero && !rightFromCompare) {
                        Expression varExpr = ops.valueToExpr(rightOp);
                        if (cmpOp == BinaryOperator.EQ) {
                            yield new UnExpr(UnaryOperator.NOT, varExpr);
                        } else if (cmpOp == BinaryOperator.NE) {
                            yield varExpr;
                        }
                    }

                    // 检测 COMPARE+CONDITION 模式
                    Value cmpVal = null;
                    if (rightOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE) {
                        cmpVal = rightOp;
                    } else if (leftOp instanceof InstructionRef ref
                            && ref.instruction().opcode() == IrOpcode.COMPARE) {
                        cmpVal = leftOp;
                    }

                    if (cmpVal != null) {
                        IrInstruction cmp = ((InstructionRef) cmpVal).instruction();
                        if (cmp.operands().size() >= 2) {
                            Expression cmpLeft = ops.valueToExpr(cmp.operands().get(0));
                            Expression cmpRight = ops.valueToExpr(cmp.operands().get(1));
                            BinaryOperator cmpBinOp = IrInstruction.binaryOpFromBytecode(
                                    insn.originalOpcode());
                            if (cmpBinOp != null) {
                                yield new BinExpr(cmpBinOp, cmpLeft, cmpRight);
                            }
                        }
                    }

                    // 常规条件(无 COMPARE 合并)
                    Expression left = ops.valueToExpr(leftOp);
                    Expression right = ops.valueToExpr(rightOp);
                    yield new BinExpr(cmpOp != null ? cmpOp : BinaryOperator.EQ, left, right);
                }
                yield new VarExpr("/* condition */");
            }

            // 一元运算
            case UNARY -> {
                if (!insn.operands().isEmpty()) {
                    UnaryOperator uop = StatementUtils.inferUnaryOp(insn.originalOpcode());
                    yield new UnExpr(uop, ops.valueToExpr(insn.operands().getFirst()));
                }
                yield new VarExpr("/* unary */");
            }

            // 方法调用——第一个操作数是接收者(非静态调用时)
            case INVOKE -> {
                // Invokedynamic(lambda / 方法引用):改为生成 LambdaExpr
                if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.INDY)) {
                    yield ops.indyTranslator().translate(insn);
                }

                List<Expression> args = new ArrayList<>();
                boolean isConstructor = insn.hasTag(
                        com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION)
                        || insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)
                        || insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR);

                String mName = insn.nameHint() != null ? insn.nameHint() : "method";

                // 第一个操作数是接收者(IrBuilder 将其存储为目标)
                int argStart = 0;
                Expression target = null;
                if (isConstructor) {
                    // 构造函数:第一个操作数是 'this' (ALOAD_0)——跳过,使用语义名称
                    argStart = 1;
                    if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.SUPER_CONSTRUCTOR)) {
                        mName = "super";
                    } else if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.THIS_CONSTRUCTOR)) {
                        mName = "this";
                    } else {
                        // 对象创建:NEW + INVOKESPECIAL <init> 模式
                        // 将 "<init>" 替换为目标类的简单名称
                        var ann = insn.getAnnotation(
                                com.bingbaihanji.bdec.semantic.SemanticTag.CONSTRUCTOR_DELEGATION);
                        if (ann != null) {
                            String targetClass = ann.getString(
                                    com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_TARGET_CLASS);
                            if (targetClass != null) {
                                int lastSlash = targetClass.lastIndexOf('/');
                                mName = lastSlash >= 0
                                        ? targetClass.substring(lastSlash + 1)
                                        : targetClass;
                            }
                        }
                    }
                } else if (insn.hasTag(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                    // 带声明类注解的静态调用 → 使用类名作为目标
                    var dcAnn = insn.getAnnotation(
                            com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS);
                    if (dcAnn != null) {
                        String dc = dcAnn.getString(
                                com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                        if (dc != null) {
                            // 静态调用目标:顶层类取包分隔后的简单名(com/foo/Bar → Bar);
                            // 嵌套类/嵌套枚举(com/foo/Outer$Inner 或默认包的
                            // Outer$Inner,如 EnumBinName$Color)取最后一个 $ 之后的段
                            // (→ Inner/Color).同一编译单元内嵌套类型以简单名可见,
                            // 与下方 FIELD_LOAD 的静态字段目标处理保持同一约定;
                            // 跨文件嵌套类(如 java/util/Map$Entry)理论上需
                            // Outer.Inner + import,此处先按同编译单元简单名处理.
                            // 匿名类($ 后跟数字)不适用此场景(源码无法对匿名类做静态调用).
                            int lastSlash = dc.lastIndexOf('/');
                            String simple = lastSlash >= 0
                                    ? dc.substring(lastSlash + 1) : dc;
                            int lastDollar = simple.lastIndexOf('$');
                            target = new VarExpr(lastDollar >= 0
                                    ? simple.substring(lastDollar + 1) : simple);
                        }
                    }
                    argStart = 0; // 所有操作数均为参数(无接收者)
                } else if (!insn.operands().isEmpty()) {
                    // 普通调用:第一个操作数是接收者 → 转为 target 表达式
                    Value firstOp = insn.operands().getFirst();
                    target = ops.valueToExpr(firstOp);
                    argStart = 1;
                } else {
                    // 无注解的静态调用——无目标
                    argStart = 0;
                }

                for (int i = argStart; i < insn.operands().size(); i++) {
                    args.add(ops.valueToExpr(insn.operands().get(i)));
                }
                yield new InvocationExpr(target, mName, args, insn.resultType());
            }

            // 类型转换
            case CAST -> {
                Expression operand = !insn.operands().isEmpty()
                        ? ops.valueToExpr(insn.operands().getFirst()) : new VarExpr("varUnresolved");
                yield new CastExpr(insn.resultType(), operand,
                        ops.renderOffsetTypeAnnotations(
                                com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_CAST,
                                insn.sourceOffset()));
            }

            // 对象创建——若已折叠则携带合并后的构造函数参数
            case NEW -> {
                NewExpr created;
                if (ops.currentNewToInit().containsKey(insn.id())) {
                    List<IrInstruction> inits = ops.currentNewToInit().get(insn.id());
                    List<Expression> ctorArgs = new ArrayList<>();
                    for (IrInstruction init : inits) {
                        for (int i = 0; i < init.operands().size(); i++) {
                            Value op = init.operands().get(i);
                            // 跳过自引用(接收者 = 此 NEW 指令)
                            if (op instanceof InstructionRef ref
                                    && ref.instruction().id() == insn.id()) {
                                continue;
                            }
                            ctorArgs.add(ops.valueToExpr(op));
                        }
                    }
                    // NewExpr 构造函数为 (type, dimensions, constructorArgs)
                    // 注意:不再调用 stripEnclosingThis,因为 AstBuilder 保留了 this$0
                    // 构造函数参数,调用处需传递 this 以保持一致性
                    created = new NewExpr(insn.resultType(), List.of(), ctorArgs,
                            List.of(), List.of(), ops.renderOffsetTypeAnnotations(
                            com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_NEW,
                            insn.sourceOffset()));
                } else {
                    // NewExpr 构造函数为 (type, dimensions, constructorArgs)
                    created = new NewExpr(insn.resultType(), List.of(), List.of(),
                            List.of(), List.of(), ops.renderOffsetTypeAnnotations(
                            com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_NEW,
                            insn.sourceOffset()));
                }
                // 菱形推断(有参):泛型类 + 存在参数个数匹配且涉及类型变量的构造器
                // → 发射 new ArrayList<>(args),让 javac 从实参重推断类型实参.
                // 无参 new 由声明目标类型置位(见 translateStmt 的 STORE 声明分支).
                if (!created.constructorArgs().isEmpty()
                        && created.instantiatedType() != null
                        && created.instantiatedType().internalName() != null
                        && com.bingbaihanji.bdec.ir.GenericMethodResolver.isGenericClass(
                        created.instantiatedType().internalName())
                        && com.bingbaihanji.bdec.ir.GenericMethodResolver.ctorParamsBindTypeVars(
                        created.instantiatedType().internalName(),
                        created.constructorArgs().size())) {
                    created = created.withDiamond();
                }
                yield created;
            }
            case NEW_ARRAY -> {
                // 从操作数中提取数组大小(由 NEWARRAY/ANEWARRAY 弹出栈的值)
                List<Expression> dims = new ArrayList<>();
                for (Value op : insn.operands()) {
                    dims.add(ops.valueToExpr(op));
                }
                if (dims.isEmpty()) {
                    dims.add(new VarExpr("varUnresolved"));
                }
                yield new NewExpr(insn.resultType(), dims, List.of(),
                        List.of(), List.of(), ops.renderOffsetTypeAnnotations(
                        com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_NEW,
                        ops.arrayExprStartOffset(insn)));
            }

            // instanceof:nameHint 携带目标类内部名
            case INSTANCE_OF -> {
                Expression obj = !insn.operands().isEmpty()
                        ? ops.valueToExpr(insn.operands().getFirst()) : new VarExpr("obj");
                JavaType checkedType = insn.nameHint() != null
                        ? JavaType.classType(insn.nameHint())
                        : JavaType.classType("java/lang/Object");
                yield new InstanceOfExpr(obj, checkedType,
                        ops.renderOffsetTypeAnnotations(
                                com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_INSTANCEOF,
                                insn.sourceOffset()));
            }

            // 数组元素加载:a[i]
            case ARRAY_LOAD -> {
                Expression arr = !insn.operands().isEmpty()
                        ? ops.valueToExpr(insn.operands().get(0)) : new VarExpr("arr");
                Expression idx = insn.operands().size() > 1
                        ? ops.valueToExpr(insn.operands().get(1)) : new VarExpr("i");
                yield new ArrayAccessExpr(arr, idx);
            }
            // 数组元素存储:a[i] = v
            case ARRAY_STORE -> {
                Expression arr = !insn.operands().isEmpty()
                        ? ops.valueToExpr(insn.operands().get(0)) : new VarExpr("arr");
                // 若数组操作数是多次引用的 NEW_ARRAY,
                // 使用临时变量而非内联 new 表达式(语义正确性).
                if (!insn.operands().isEmpty()
                        && insn.operands().get(0) instanceof InstructionRef ref
                        && ops.currentMultiRefArrayVar().containsKey(ref.instruction().id())) {
                    arr = new VarExpr(ops.currentMultiRefArrayVar().get(ref.instruction().id()));
                }
                Expression idx = insn.operands().size() > 1
                        ? ops.valueToExpr(insn.operands().get(1)) : new VarExpr("i");
                Expression val = insn.operands().size() > 2
                        ? ops.valueToExpr(insn.operands().get(2)) : new VarExpr("varUnresolved");
                yield new AssignExpr(new ArrayAccessExpr(arr, idx), val);
            }

            // 数组长度
            case ARRAY_LENGTH -> {
                Expression arr = !insn.operands().isEmpty()
                        ? ops.valueToExpr(insn.operands().getFirst()) : new VarExpr("arr");
                yield new FieldAccessExpr(arr, "length");
            }

            // 自增指令(IINC)——操作数: [readVar, writeVar, ConstantValue(incr)]
            case INC -> {
                if (insn.operands().size() >= 3 && insn.operands().getFirst() instanceof Variable v) {
                    Value incr = insn.operands().get(2); // 索引 2 是增量值
                    VarExpr var = ops.varToExpr(v);
                    Expression rhs = ops.valueToExpr(incr);
                    // x += c → 若 c == 1 则转为 x++,c == -1 转为 x--
                    if (rhs instanceof com.bingbaihanji.bdec.ast.expr.LitExpr lr
                            && lr.value() instanceof Integer i) {
                        if (i == 1) {
                            yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                    com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_INC, var);
                        }
                        if (i == -1) {
                            yield new com.bingbaihanji.bdec.ast.expr.UnExpr(
                                    com.bingbaihanji.bdec.ast.expr.UnaryOperator.POST_DEC, var);
                        }
                    }
                    yield new AssignExpr(var, new BinExpr(BinaryOperator.ADD, var, rhs));
                }
                yield new VarExpr("/* inc */");
            }

            // 抛出异常
            case THROW -> !insn.operands().isEmpty() ? ops.valueToExpr(insn.operands().getFirst()) : new VarExpr("ex");

            // PHI——选取属于当前分支上下文的操作数.
            // 如果已知当前正在翻译哪些块(branchBlocks 提示),
            // 选取定义指令位于这些块中的 PHI 操作数.
            // 否则选取第一个非平凡操作数.
            case PHI -> {
                // 条件赋值折叠已把此 PHI 折叠为三元表达式(见 IfTranslator)——
                // 后续 STORE 翻译时直接返回折叠结果,避免丢 false 分支值.
                Expression replaced = ops.phiReplacements().get(insn.id());
                if (replaced != null) {
                    yield replaced;
                }
                Expression resolved = null;
                if (ops.currentBranchBlocks() != null) {
                    for (Value op : insn.operands()) {
                        if (op instanceof InstructionRef ref
                                && ops.currentBranchBlocks().contains(ref.instruction().blockId())) {
                            resolved = translateExpr(ref.instruction());
                            break;
                        }
                    }
                }
                if (resolved == null) {
                    // value 位置(无分支上下文)的三元重建:PHI 作为 CONDITION/BINARY
                    // 等指令的操作数被消费时,按 CFG 菱形还原为 CondExpr,避免取首
                    // 操作数静默丢 false 分支值.
                    if (ops.currentBranchBlocks() == null && ops.currentIr() != null) {
                        resolved = IfTranslator.resolvePhiAsTernary(ops, insn, ops.currentIr());
                    }
                }
                if (resolved == null) {
                    for (Value op : insn.operands()) {
                        if (op instanceof InstructionRef ref) {
                            resolved = translateExpr(ref.instruction());
                            break;
                        }
                        if (op instanceof ConstantValue(Object value, JavaType type)) {
                            resolved = new LitExpr(value, type);
                            break;
                        }
                        if (op instanceof com.bingbaihanji.bdec.ir.DynamicConstantValue dcv) {
                            resolved = ExpressionTranslator.dynamicConstToExpr(dcv);
                            break;
                        }
                        if (op instanceof Variable v) {
                            resolved = ops.varToExpr(v);
                            break;
                        }
                    }
                }
                yield resolved != null ? resolved : new VarExpr("merge" + insn.id());
            }

            default -> new VarExpr("/* " + insn.opcode() + " */");
        };
    }

}
