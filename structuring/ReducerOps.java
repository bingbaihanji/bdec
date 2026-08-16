package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.TypePathElement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归约器回调接口——把 {@link BlockReducer} 中依赖归约状态的翻译能力
 * 以回调形式提供给按模式拆分的翻译器.
 *
 * <p>设计参照 Vineflower 的"每模式一处理器"结构
 * ({@code EliminateLoopsHelper}/{@code IfHelper} 等聚焦助手类共享
 * 方法上下文)与 CFR 的 {@code OperationFactory} 模式
 * (操作码翻译与状态解耦):翻译器({@link SwitchTranslator},
 * {@link LoopTranslator})只关心自身的模式,所需的归约状态
 * (表达式翻译,作用域追踪,PHI 分支上下文)全部通过本接口回调
 * {@link BlockReducer},避免翻译逻辑与归约状态相互纠缠.</p>
 */
public interface ReducerOps {

    /** 将 Value 转为表达式(含变量存储内联解析). */
    Expression valueToExpr(Value v);

    /** 翻译块组中非条件的有副作用语句(条件前的语句). */
    List<Statement> translateHeaderNonCondition(BlockGroup group, LinearIr ir);

    /** 翻译单个块组为语句列表. */
    List<Statement> translateBlockGroup(BlockGroup group, LinearIr ir);

    /** 翻译单个块组为语句(含内联与后置自增折叠). */
    Statement translateGroup(BlockGroup group, LinearIr ir);

    /** 将处理器指令(去除最后的 THROW)翻译为 Statement 体. */
    Statement translateHandlerWithoutThrow(TryCatchInfo info, LinearIr ir,
                                           List<IrInstruction> handlerInsns);

    /** 待折叠的后置自增挂起语句(翻译器间传递),可为 null. */
    Statement pendingPostInc();

    /** 设置待折叠的后置自增挂起语句. */
    void setPendingPostInc(Statement s);

    /** 从块头提取并简化条件表达式. */
    Expression extractConditionFromHeader(BasicBlock header, LinearIr ir);

    /** 扫描所有组与全部 IR 指令查找最靠前的 CONDITION 并翻译为表达式. */
    Expression extractConditionFromAllGroups(List<BlockGroup> groups, LinearIr ir);

    /** 从组内提取条件表达式. */
    Expression extractCondition(BlockGroup group, LinearIr ir);

    /** 在当前作用域注册变量名(受外层作用域约束,JLS 6.4 不可遮蔽). */
    boolean tryDeclareVar(String name);

    /** 压入新的变量声明作用域(分支体翻译前调用). */
    void pushDeclaredScope();

    /** 弹出变量声明作用域(分支体翻译后调用). */
    void popDeclaredScope();

    /** 当前 reduce() 调用的 try-catch 注解列表(供分支体翻译使用). */
    List<TryCatchInfo> tryCatchAnnotations();

    /** 当前 PHI 解析的分支块上下文(块 id 集合). */
    Set<Integer> currentBranchBlocks();

    /** 设置 PHI 解析的分支块上下文. */
    void setCurrentBranchBlocks(Set<Integer> blockIds);

    /** 在 follow 块中按当前分支上下文解析 PHI 值. */
    Expression resolvePhiAt(BasicBlock follow, LinearIr ir);

    /** 注册 PHI 折叠结果:后续 STORE 翻译时按此映射替换 PHI 解析. */
    void registerPhiReplacement(int phiId, Expression expr);

    /** 注册待跳过的指令 ID(switch 表达式 case 体已把值解析进各 case,follow 的
     *  STORE/RETURN←PHI 不再单独发射). */
    void registerSkippedInstruction(int insnId);

    /** 该指令 ID 是否已被注册为跳过. */
    boolean isSkippedInstruction(int insnId);

    /** 块上是否带循环注解. */
    LoopInfo loopAnnotation(BasicBlock b);

    /** 块上是否带 switch 注解. */
    SwitchInfo switchAnnotation(BasicBlock b);

    // ── 表达式翻译上下文(供 ExprTranslator 回调) ───────────────

    /** NEW 指令 → 其 <init> 调用列表(NEW+INVOKE 合并). */
    Map<Integer, List<IrInstruction>> currentNewToInit();

    /** 多引用数组临时变量映射. */
    Map<Integer, String> currentMultiRefArrayVar();

    /** 当前方法的线性 IR. */
    LinearIr currentIr();

    /** PHI 折叠结果映射(后续 STORE 翻译替换 PHI 解析). */
    Map<Integer, Expression> phiReplacements();

    /** invokedynamic lambda/方法引用翻译器. */
    IndyTranslator indyTranslator();

    /** 当前方法返回类型是否为 boolean. */
    boolean currentMethodReturnsBoolean();

    /** 当前方法的泛型返回类型(签名优先). */
    JavaType genericMethodReturnType();

    /** Variable → VarExpr(含 slot-0 版本化变量的 this 区分). */
    VarExpr varToExpr(Variable var);

    /** CONST IR → LitExpr. */
    Expression constToExpr(IrInstruction insn);

    /** 按类型路径渲染指令偏移处的 JSR-308 类型注解. */
    Map<List<TypePathElement>, List<String>> renderOffsetTypeAnnotations(
            int targetType, int offset);

    /** 是否有局部变量与给定字段名相同(剥离 this. 前缀时的歧义). */
    boolean localVarShadowsField(String fieldName);

    /** 值是否为已折叠 PHI 替换的布尔变量. */
    boolean isBooleanPhiReplacedVariable(Value v);

    /**
     * 变量是否在布尔上下文中使用:作为 boolean 返回方法的 return 值,或作为
     * 逻辑运算(BINARY AND/OR/XOR)操作数且另一操作数为布尔值.
     * 用于把字节码中按 int 0/1 存储的布尔局部变量(如 {@code changed |= add()})
     * 声明收窄为 boolean,否则 {@code int x = 0} 与 boolean |= 无法编译.
     */
    boolean isVarInBooleanContext(Value v);

    /** 当前方法是否为实例方法(用于 this 字段加载的简写判定). */
    boolean isInstanceMethod();

    /** NEW_ARRAY 表达式起始偏移(javac 数组创建注解偏移指向表达式起始). */
    int arrayExprStartOffset(IrInstruction insn);
}
