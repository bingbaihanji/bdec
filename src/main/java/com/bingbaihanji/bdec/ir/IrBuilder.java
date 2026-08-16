package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.opcode.Opcode;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.semantic.SemanticAnnotation;
import com.bingbaihanji.bdec.semantic.SemanticTag;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.type.TypeResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IR构建器——将栈式字节码转换为寄存器式IR的栈模拟引擎.
 * <p>
 * 每条JVM指令通过模拟其在操作数栈和局部变量表上的效果来处理.
 * 主方法{@link #simulateBlock}的switch语句将各指令分发到对应类别的处理函数.
 * 元数据(原始字节码操作码,字段名/方法名)保留在{@link IrInstruction}中,
 * 以便下游pass能生成正确的运算符和名称.
 * </p>
 *
 * <h3>处理架构</h3>
 * <ul>
 *   <li>{@link #handleConstant} — 常量加载(iconst_0,ldc等)</li>
 *   <li>{@link #handleLoad} / {@link #handleStore} — 局部变量读写</li>
 *   <li>{@link #handleArithmetic} / {@link #handleNegate} — 算术运算</li>
 *   <li>{@link #handleComparison} / {@link #handleCondition} — 比较与分支</li>
 *   <li>{@link #handleFieldLoad} / {@link #handleFieldStore} — 字段访问</li>
 *   <li>{@link #handleInvoke} / {@link #handleInvokeDynamic} — 方法调用</li>
 *   <li>{@link #handleNew} / {@link #handleNewArray} — 对象与数组创建</li>
 *   <li>{@link #handleConversion} / {@link #handleCheckCast} — 类型转换</li>
 * </ul>
 */
public final class IrBuilder {

    /** 下一条指令的ID计数器 */
    private int nextInsnId = 0;

    private MethodModel currentMethod = null;

    /** 来自类文件的引导方法列表,用于 invokedynamic 的解析. */
    private List<BootstrapMethodEntry> currentBootstrapMethods = java.util.Collections.emptyList();

    /**
     * 当前被反编译的类(可为 null).用于泛型返回推断:类自身方法
     * ({@code Cache<K,V>} 接口的 {@code get}) 的字节码签名被擦除,
     * 但 Signature 属性完整可得——据此把 {@code this.get(key)} 的返回
     * 类型还原为 {@code V} 而非 {@code Object}.仅 build 期间有效.
     */
    private ClassFileModel selfClass = null;

    /** handleInvoke 解析到的自类方法泛型参数(下一条 invoke 指令设置用). */
    private List<JavaType> pendingGenericParams = null;

    /**
     * 反编译上下文(可为 null):用于解析非当前类(如匿名类的外层 ArrayMap)
     * 的方法泛型签名,供参数强转.仅 build 期间有效.
     */
    private DecompileContext decompileContext = null;

    /**
     * 判断一个值是否为类别2类型(long或double,在JVM操作数栈中占用两个槽位).
     */
    private static boolean isCategory2(Value v) {
        return v.type() != null && (v.type().kind() == TypeKind.LONG
                || v.type().kind() == TypeKind.DOUBLE);
    }

    /**
     * 沿InstructionRef链追溯底层常量值.
     * 如果值链末端是CONST指令,则提取其ConstantValue操作数.
     */
    private static ConstantValue unwrapConstant(Value v) {
        if (v instanceof ConstantValue cv) {
            return cv;
        }
        if (v instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            if (def.opcode() == IrOpcode.CONST && !def.operands().isEmpty()) {
                Value inner = def.operands().getFirst();
                if (inner instanceof ConstantValue cv) {
                    return cv;
                }
            }
        }
        return null;
    }

    // ── 基本块排序 ──────────────────────────────────────────────────

    // ── 前驱合并 ────────────────────────────────────────────────────

    /**
     * 按变量的 LVT 声明类型折叠被存常量(仅 boolean/char).
     *
     * <p>JVM 操作数栈上这些窄类型一律表现为 int(ICONST/BIPUSH 等):
     * <ul>
     *   <li>boolean 必须折叠——否则渲染成非法的 {@code boolean b = 1};</li>
     *   <li>char 折叠为字符字面量(与 handleInvoke 的 char 参数折叠一致),
     *       否则 {@code char c = 59} 失真;</li>
     *   <li>byte/short 不折叠——常量整型直接用于 {@code byte b = 10} 声明
     *       是合法的(常量收窄赋值),而折叠后经发射器会多出 {@code (byte) }
     *       冗余强转.</li>
     * </ul>
     * 沿 InstructionRef 链追溯底层 CONST 常量,按声明类型重标.</p>
     */
    private static Value foldStoreConstant(Value val, JavaType declaredType) {
        if (declaredType == null) {
            return val;
        }
        ConstantValue cv = unwrapConstant(val);
        if (cv == null) {
            return val;
        }
        return switch (declaredType.kind()) {
            case BOOLEAN -> cv.value() instanceof Integer i
                    ? new ConstantValue(i != 0, JavaType.BOOLEAN) : val;
            case CHAR -> cv.value() instanceof Number n
                    && n.intValue() >= 0 && n.intValue() <= 0xFFFF
                    ? new ConstantValue((char) n.intValue(), JavaType.CHAR) : val;
            default -> val;
        };
    }

    // ── 主模拟 — 模拟一个基本块 ───────────────────────────────────────

    /**
     * 类签名中的类型参数(如 {@code [K, V]}).
     */
    private static JavaType[] classTypeParams(String classSignature) {
        if (classSignature == null || classSignature.isEmpty()) {
            return new JavaType[0];
        }
        List<String> names = SignatureParser.extractTypeParams(classSignature);
        JavaType[] result = new JavaType[names.size()];
        for (int i = 0; i < names.size(); i++) {
            result[i] = JavaType.typeVariable(names.get(i));
        }
        return result;
    }

    /** 方法签名中的泛型参数类型(含类型变量,如 (TK;TV;) → [K, V]);无签名返回 null. */
    private static JavaType[] genericParamTypes(MethodModel method) {
        String sig = method.signature();
        if (sig == null || sig.isEmpty()) {
            return null;
        }
        try {
            JavaType[] parts = SignatureParser.parseMethodSignature(sig);
            if (parts == null || parts.length < 1) {
                return null;
            }
            return java.util.Arrays.copyOfRange(parts, 0, parts.length - 1);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 在给定类文件模型中按 (方法名, 描述符) 查找方法泛型签名. */
    private static SelfMethodSig findInModel(ClassFileModel model, String methodName,
                                             String descriptor) {
        if (model == null) {
            return null;
        }
        for (MethodModel m : model.methods()) {
            if (!methodName.equals(m.name()) || !descriptor.equals(m.descriptor())) {
                continue;
            }
            String sig = m.signature();
            if (sig == null || sig.isEmpty()) {
                return null;
            }
            try {
                JavaType[] parts = SignatureParser.parseMethodSignature(sig);
                if (parts == null || parts.length < 1) {
                    return null;
                }
                JavaType[] sigParams = java.util.Arrays.copyOfRange(parts, 0, parts.length - 1);
                return new SelfMethodSig(sigParams, parts[parts.length - 1]);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 通过对每个基本块进行符号执行,从控制流图构建线性IR.
     *
     * @param cfg              控制流图
     * @param method           方法模型
     * @param constantPool     常量池
     * @param bootstrapMethods 引导方法列表
     * @return 构建完成的线性IR
     */
    public LinearIr build(ControlFlowGraph cfg, MethodModel method,
                          ConstantPoolEntry[] constantPool,
                          List<BootstrapMethodEntry> bootstrapMethods) {
        return build(cfg, method, constantPool, bootstrapMethods, null, null);
    }

    /**
     * 带自类上下文的重载. {@code selfClass} 为当前被反编译的类文件
     * (可为 null),用于:(1) {@code this} 类型设为 {@code Cache<K,V>}
     * 而非 {@code Object};(2) 类自身方法的泛型返回类型推断.
     */
    public LinearIr build(ControlFlowGraph cfg, MethodModel method,
                          ConstantPoolEntry[] constantPool,
                          List<BootstrapMethodEntry> bootstrapMethods,
                          ClassFileModel selfClass) {
        return build(cfg, method, constantPool, bootstrapMethods, selfClass, null);
    }

    /**
     * 完整重载:{@code context} 用于解析非当前类(如匿名类外层 ArrayMap)的
     * 方法泛型签名,供参数强转.
     */
    public LinearIr build(ControlFlowGraph cfg, MethodModel method,
                          ConstantPoolEntry[] constantPool,
                          List<BootstrapMethodEntry> bootstrapMethods,
                          ClassFileModel selfClass,
                          DecompileContext context) {
        this.selfClass = selfClass;
        this.decompileContext = context;
        this.currentBootstrapMethods = bootstrapMethods != null
                ? bootstrapMethods : Collections.emptyList();
        List<IrInstruction> allInstructions = new ArrayList<>();
        List<Variable> variables = new ArrayList<>();
        Map<BasicBlock, FrameState> blockOutputs = new HashMap<>();

        List<BasicBlock> blocks = orderBlocks(cfg);

        int maxLocals = method.maxLocals();
        if (maxLocals <= 0) {
            maxLocals = method.isStatic() ? 0 : 1;
        }

        // 使用具有正确Java类型的参数变量预填充初始FrameState.
        // 这对于boolean参数至关重要:JVM使用int类型(ILOAD/ISTORE),
        // 但实际Java类型是boolean.没有这些信息,下游pass无法区分
        // "boolean flag == 0"(→ !flag)和 "int x == 0".
        FrameState initialFrame = FrameState.withLocals(maxLocals);
        Value[] initLocals = initialFrame.locals();
        int slot = 0;
        if (!method.isStatic()) {
            // 槽位0 = 'this'.类型设为自类泛型形态(如 Cache<K,V>),而非
            // Object——否则 this.get(key) 的接收者类型无实参,泛型返回
            // 推断("this.get 返回 V")无法触发,get 被擦成 Object.
            JavaType thisType = selfType();
            Variable thisVar = new Variable(slot, 0, thisType, false, slot);
            thisVar.setName("this");
            variables.add(thisVar);
            initLocals[slot] = thisVar;
            slot++;
        }
        if (method.parameterTypes() != null) {
            // 泛型签名参数(如 (TK;TV;)):参数变量用类型变量 K/V 而非擦除的 Object,
            // 使调用点实参保持 K/V,避免冗余强转(如 put((K) key) 的 key 本就是 K).
            JavaType[] genericParams = genericParamTypes(method);
            int paramIdx = 0;
            for (JavaType pt : method.parameterTypes()) {
                if (slot < maxLocals) {
                    JavaType paramType = pt;
                    if (genericParams != null && paramIdx < genericParams.length
                            && genericParams[paramIdx].kind() == TypeKind.TYPE_VARIABLE) {
                        paramType = genericParams[paramIdx];
                    }
                    Variable pv = new Variable(slot, 0, paramType, true, slot);
                    // 如果有局部变量表名称则使用;
                    // 否则使用 "param"+索引 作为回退,与 AstBuilder 的
                    // buildParameterNames 保持一致——否则条件/表达式中
                    // 的参数引用会显示为未声明的 varN.
                    String lvtName = method.localVarNames().get(slot);
                    if (lvtName != null) {
                        pv.setName(lvtName);
                    } else {
                        pv.setName("param" + paramIdx);
                    }
                    variables.add(pv);
                    initLocals[slot] = pv;
                    slot++;
                    paramIdx++;
                    // long和double在JVM中占用两个槽位
                    if (pt.kind() == TypeKind.LONG
                            || pt.kind() == TypeKind.DOUBLE) {
                        slot++;
                    }
                }
            }
        }

        for (BasicBlock block : blocks) {
            FrameState entry = mergePredecessorStates(block, blockOutputs, cfg, allInstructions, variables);
            if (entry == null) {
                entry = initialFrame.copy();
            }
            // 异常处理器入口:JVM 在栈顶隐式压入异常对象.
            // 若不补上,处理器首条 astore 弹出空栈,STORE 指令丢失,
            // catch 体内的引用会错误解析为槽位上的过期变量.
            boolean isHandlerEntry = cfg.incomingOf(block).stream()
                    .anyMatch(e -> e.kind() == EdgeKind.EXCEPTION);
            if (isHandlerEntry) {
                String catchType = null;
                for (var r : cfg.exceptionRanges()) {
                    if (r.handlerBlock() == block) {
                        catchType = r.catchType();
                        break;
                    }
                }
                JavaType exType = catchType != null
                        ? JavaType.classType(catchType)
                        : JavaType.classType("java/lang/Throwable");
                entry.stack().push(new Variable(-1, 0, exType, false, -1));
            }
            FrameState exit = simulateBlock(block, entry, allInstructions, variables, cfg, method, constantPool);
            blockOutputs.put(block, exit);
        }

        return new LinearIr(method, cfg, allInstructions, variables);
    }

    /**
     * {@code this} 的类型:自类泛型形态(如 {@code Cache<K,V>}).
     *
     * <p>从类 Signature 属性提取类型参数名,构造
     * {@code TYPE_VARIABLE(K)} 作为类型实参;无签名或解析失败时
     * 回退 Object(保持旧行为).</p>
     */
    private JavaType selfType() {
        if (selfClass != null) {
            String sig = selfClass.signature();
            if (sig != null && !sig.isEmpty()) {
                try {
                    List<String> typeParams = SignatureParser.extractTypeParams(sig);
                    if (!typeParams.isEmpty()) {
                        String internal = selfClass.internalName();
                        List<JavaType> args = new ArrayList<>(typeParams.size());
                        for (String tp : typeParams) {
                            args.add(JavaType.typeVariable(tp));
                        }
                        return new JavaType(TypeKind.CLASS, internal,
                                "L" + internal + ";", args, 0);
                    }
                } catch (Exception ignored) {
                    // 签名解析失败:回退 Object
                }
            }
        }
        return JavaType.classType("java/lang/Object");
    }

    /**
     * 声明类方法签名查找:按 (声明类, 方法名, 描述符) 定位方法,取 Signature
     * 属性(如 {@code (TK;)TV;})解析出含类型变量的参数/返回类型.先在当前类
     * (selfClass)中查,再尝试经 {@link DecompileContext} 加载声明类字节码
     * (覆盖匿名类调用外层类方法等跨类场景).匹配失败/无签名返回 {@code null}.
     * 调用方分别做返回类型推断(经 {@link GenericMethodResolver#inferFromSignature})
     * 与参数强转(记录泛型参数).
     */
    private SelfMethodSig findDeclaredMethodSignature(String declaringClass,
                                                      String methodName, String descriptor) {
        if (declaringClass == null) {
            return null;
        }
        String selfName = selfClass != null ? selfClass.internalName() : null;
        if (declaringClass.equals(selfName)) {
            SelfMethodSig self = findInModel(selfClass, methodName, descriptor);
            if (self != null) {
                return self;
            }
        }
        // 跨类:匿名类调用外层方法等——经 context 加载声明类字节码解析签名
        if (decompileContext != null && !declaringClass.equals(selfName)) {
            try {
                byte[] bytes = decompileContext.loadClassBytes(declaringClass);
                if (bytes != null) {
                    ClassFileModel cfm = new ClassFileReader().read(declaringClass, bytes);
                    return findInModel(cfm, methodName, descriptor);
                }
            } catch (Exception ignored) {
                // 加载/解析失败:保持默认行为
            }
        }
        return null;
    }

    /**
     * 返回基本块的排序列表,使每个块的前驱尽可能在其之前处理.
     * 使用工作列表算法:仅当某块的所有前驱均已处理时才发射该块,
     * 对于不可归约循环(irreducible loops)回退到DFS遍历.
     * 这种方式确保了在汇合点能够正确创建PHI节点.
     */
    private List<BasicBlock> orderBlocks(ControlFlowGraph cfg) {
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> emitted = new HashSet<>();
        Set<BasicBlock> inQueue = new HashSet<>();
        Deque<BasicBlock> queue = new ArrayDeque<>();

        // 从入口块的后继开始(入口块本身不需要模拟)
        for (BasicBlock succ : cfg.successorsOf(cfg.entryBlock())) {
            if (succ != cfg.exitBlock()) {
                queue.add(succ);
                inQueue.add(succ);
            }
        }

        int maxIter = cfg.blockCount() * 4; // 不可归约循环的安全上限
        while (!queue.isEmpty() && maxIter-- > 0) {
            BasicBlock b = queue.poll();
            inQueue.remove(b);
            if (emitted.contains(b)) {
                continue;
            }

            // 可发射该块的条件:所有前驱均已发射(或者是入口/出口块),
            // 或等待次数过多时使用回退策略
            boolean allPredsReady = true;
            for (BasicBlock pred : cfg.predecessorsOf(b)) {
                if (pred != cfg.entryBlock() && pred != cfg.exitBlock()
                        && !emitted.contains(pred)) {
                    allPredsReady = false;
                    break;
                }
            }

            if (allPredsReady) {
                emitted.add(b);
                result.add(b);
                // 将后继加入队列
                for (BasicBlock succ : cfg.successorsOf(b)) {
                    if (succ != cfg.exitBlock() && !emitted.contains(succ)
                            && !inQueue.contains(succ)) {
                        queue.add(succ);
                        inQueue.add(succ);
                    }
                }
            } else {
                // 重新放回队尾——等前驱处理完后重试
                queue.add(b);
                inQueue.add(b);
            }
        }

        // 回退:任何仍未处理的基本块(不可归约循环中的块)——使用DFS
        if (emitted.size() < cfg.blockCount() - 2) { // 减去入口+出口
            for (BasicBlock b : cfg.blocks()) {
                if (b != cfg.entryBlock() && b != cfg.exitBlock()
                        && !emitted.contains(b)) {
                    result.add(b);
                    emitted.add(b);
                }
            }
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  按操作码类别的处理函数
    // ═══════════════════════════════════════════════════════════════════

    // ── 栈操作 ─────────────────────────────────────────────────────

    /**
     * 合并一个基本块所有前驱的帧状态.
     *
     * <p><b>变量槽位合并</b>:从所有前驱状态中选取每个槽位的最新版本,
     * 确保通过任意路径的store操作在主路径中可见.</p>
     *
     * <p><b>操作数栈合并</b>:JVM验证保证在汇合点操作数栈为空
     * (异常处理器例外,其栈上有且仅有一个元素——抛出的异常).
     * 当多个前驱在栈上推送了相同深度的值时,创建stack-PHI节点.</p>
     *
     * @param block        目标基本块
     * @param outputs      各基本块执行后的帧状态映射
     * @param cfg          控制流图
     * @param instructions 正在构建的IR指令列表
     * @param variables    变量列表
     * @return 合并后的帧状态,如果无前驱状态则返回 {@code null}
     */
    private FrameState mergePredecessorStates(BasicBlock block,
                                              Map<BasicBlock, FrameState> outputs,
                                              ControlFlowGraph cfg,
                                              List<IrInstruction> instructions,
                                              List<Variable> variables) {
        List<BasicBlock> preds = cfg.predecessorsOf(block);
        if (preds.isEmpty()) {
            return null;
        }
        if (preds.size() == 1) {
            return outputs.get(preds.get(0));
        }

        // 收集所有前驱状态
        List<FrameState> predStates = new ArrayList<>();
        for (BasicBlock pred : preds) {
            FrameState state = outputs.get(pred);
            if (state != null) {
                predStates.add(state);
            }
        }
        if (predStates.isEmpty()) {
            return null;
        }
        if (predStates.size() == 1) {
            return predStates.get(0).copy();
        }

        // 确定所有前驱中的最大局部变量槽位数
        int maxLocals = 0;
        for (FrameState s : predStates) {
            maxLocals = Math.max(maxLocals, s.locals().length);
        }

        // 合并局部变量:对每个槽位选所有前驱中版本号最大的变量
        // 这确保通过任意路径的store操作贡献其最新变量版本
        Value[] mergedLocals = new Value[maxLocals];
        for (int slot = 0; slot < maxLocals; slot++) {
            Variable best = null;
            for (FrameState s : predStates) {
                if (slot < s.locals().length && s.locals()[slot] instanceof Variable v) {
                    if (best == null || v.version() > best.version()) {
                        best = v;
                    }
                }
            }
            mergedLocals[slot] = best;
        }

        // 合并操作数栈:检测是否有前驱是异常边.
        // 异常处理器的栈 = [thrown_exception];普通汇合点栈为空(JVM验证保证).
        boolean hasExceptionEdge = false;
        for (BasicBlock pred : preds) {
            for (var edge : cfg.incomingOf(block)) {
                if (edge.source().equals(pred)
                        && edge.kind() == EdgeKind.EXCEPTION) {
                    hasExceptionEdge = true;
                    break;
                }
            }
            if (hasExceptionEdge) {
                break;
            }
        }

        Deque<Value> mergedStack;
        if (hasExceptionEdge && !predStates.get(0).stack().isEmpty()) {
            // 异常处理器:保留异常引用在栈上
            mergedStack = new ArrayDeque<>(predStates.get(0).stack());
        } else if (!hasExceptionEdge) {
            // 多前驱普通汇合:检查所有前驱推送的值深度是否相同.
            // 若相同则创建stack-PHI节点.
            boolean allSameDepth = true;
            int depth = predStates.get(0).stack().size();
            for (FrameState ps : predStates) {
                if (ps.stack().size() != depth) {
                    allSameDepth = false;
                    break;
                }
            }
            if (allSameDepth && depth > 0) {
                // 为每个栈槽位创建PHI
                Deque<Value> phiStack = new ArrayDeque<>();
                // 从底到顶构建栈
                List<List<Value>> slotValues = new ArrayList<>();
                for (int i = 0; i < depth; i++) {
                    slotValues.add(new ArrayList<>());
                }
                for (FrameState ps : predStates) {
                    int si = 0;
                    for (Value v : ps.stack()) {
                        slotValues.get(si++).add(v);
                    }
                }
                for (int si = 0; si < depth; si++) {
                    List<Value> phiOps = slotValues.get(si);
                    JavaType phiType = phiOps.get(0).type();
                    IrInstruction phi = new IrInstruction(nextId(), IrOpcode.PHI,
                            phiType, phiOps, -1, block.id());
                    instructions.add(phi);
                    phi.setResultValue(new InstructionRef(phi, phiType));
                    phiStack.addLast(new InstructionRef(phi, phiType));
                }
                mergedStack = phiStack;
            } else {
                mergedStack = new ArrayDeque<>();
            }
        } else {
            // 正常汇合点:根据JVM验证,栈必须为空
            mergedStack = new ArrayDeque<>();
        }

        return new FrameState(mergedStack, mergedLocals);
    }

    /**
     * 对一个基本块进行符号执行.每条操作码分发到对应类别的处理函数,
     * 处理函数会保留字节码元数据,供下游进行运算符和名称解析.
     */
    private FrameState simulateBlock(BasicBlock block, FrameState entry,
                                     List<IrInstruction> instructions,
                                     List<Variable> variables,
                                     ControlFlowGraph cfg, MethodModel method,
                                     ConstantPoolEntry[] cp) {
        this.currentMethod = method;
        Deque<Value> stack = new ArrayDeque<>(entry.stack());
        Value[] locals = entry.locals().clone();

        int blockId = block.id();

        for (Instruction insn : block.instructions()) {
            int offset = insn.offset();
            Opcode op;
            try {
                op = Opcode.byCode(insn.opcode());
            } catch (IllegalArgumentException e) {
                continue;
            }

            // ── 按类别分发到处理函数 ──────────────
            switch (op) {
                // 栈操作
                case NOP -> {
                }
                case POP, POP2 -> handlePop(op, stack);
                case DUP, DUP_X1, DUP_X2, DUP2, SWAP -> handleDup(op, stack);

                // 常量
                case ACONST_NULL, ICONST_M1, ICONST_0, ICONST_1, ICONST_2,
                     ICONST_3, ICONST_4, ICONST_5, LCONST_0, LCONST_1,
                     FCONST_0, FCONST_1, FCONST_2, DCONST_0, DCONST_1,
                     BIPUSH, SIPUSH -> handleConstant(op, insn, stack, instructions, offset, blockId);
                case LDC, LDC_W, LDC2_W -> handleLdc(insn, stack, cp, instructions, offset, blockId);

                // 加载
                case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3,
                     LLOAD, LLOAD_0, LLOAD_1, LLOAD_2, LLOAD_3,
                     FLOAD, FLOAD_0, FLOAD_1, FLOAD_2, FLOAD_3,
                     DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3,
                     ALOAD, ALOAD_0, ALOAD_1, ALOAD_2, ALOAD_3 ->
                        handleLoad(op, insn, stack, locals, variables, instructions, offset, blockId);

                // 存储
                case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3,
                     LSTORE, LSTORE_0, LSTORE_1, LSTORE_2, LSTORE_3,
                     FSTORE, FSTORE_0, FSTORE_1, FSTORE_2, FSTORE_3,
                     DSTORE, DSTORE_0, DSTORE_1, DSTORE_2, DSTORE_3,
                     ASTORE, ASTORE_0, ASTORE_1, ASTORE_2, ASTORE_3 ->
                        handleStore(op, insn, stack, locals, variables, instructions, offset, blockId);

                // 整型递增
                case IINC -> handleIinc(insn, variables, instructions, offset, blockId, locals);

                // 算术运算(int)
                case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR ->
                        handleArithmetic(op, stack, instructions, JavaType.INT, offset, blockId);
                case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR ->
                        handleArithmetic(op, stack, instructions, JavaType.LONG, offset, blockId);
                case FADD, FSUB, FMUL, FDIV, FREM ->
                        handleArithmetic(op, stack, instructions, JavaType.FLOAT, offset, blockId);
                case DADD, DSUB, DMUL, DDIV, DREM ->
                        handleArithmetic(op, stack, instructions, JavaType.DOUBLE, offset, blockId);
                case INEG, LNEG, FNEG, DNEG -> handleNegate(op, stack, instructions, offset, blockId);

                // 比较 —— 传入op以保留字节码用于运算符推断
                case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> handleComparison(op, stack, instructions, offset, blockId);

                // 返回
                case RETURN -> instructions.add(IrInstruction.returnInsn(nextId(), null, offset, blockId));
                case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN ->
                        instructions.add(IrInstruction.returnInsn(nextId(), stack.pop(), offset, blockId));

                // 字段 —— 传入 insn+cp 用于名称解析
                case GETSTATIC, GETFIELD -> handleFieldLoad(op, insn, stack, instructions, cp, offset, blockId);
                case PUTSTATIC, PUTFIELD -> handleFieldStore(op, insn, stack, instructions, cp, offset, blockId);

                // 方法调用
                case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE ->
                        handleInvoke(op, insn, stack, instructions, cp, offset, blockId);
                case INVOKEDYNAMIC -> handleInvokeDynamic(insn, stack, instructions, cp, offset, blockId);

                // 对象 / 数组
                case NEW -> handleNew(insn, stack, instructions, cp, offset, blockId);
                case NEWARRAY -> handleNewPrimitiveArray(insn, stack, instructions, offset, blockId);
                case ANEWARRAY -> handleNewArray(op, insn, stack, instructions, cp, offset, blockId);
                case ARRAYLENGTH -> handleArrayLength(stack, instructions, offset, blockId);
                // 数组元素加载:弹出索引,弹出数组 → 压入元素
                case IALOAD, BALOAD, CALOAD, SALOAD ->
                        handleArrayLoad(stack, instructions, JavaType.INT, offset, blockId, op.code());
                case LALOAD -> handleArrayLoad(stack, instructions, JavaType.LONG, offset, blockId, op.code());
                case FALOAD -> handleArrayLoad(stack, instructions, JavaType.FLOAT, offset, blockId, op.code());
                case DALOAD -> handleArrayLoad(stack, instructions, JavaType.DOUBLE, offset, blockId, op.code());
                case AALOAD -> handleArrayLoad(stack, instructions,
                        JavaType.classType("java/lang/Object"), offset, blockId, op.code());
                // 数组元素存储:弹出值,弹出索引,弹出数组
                case IASTORE, BASTORE, CASTORE, SASTORE ->
                        handleArrayStore(stack, instructions, JavaType.INT, offset, blockId, op.code());
                case LASTORE -> handleArrayStore(stack, instructions, JavaType.LONG, offset, blockId, op.code());
                case FASTORE -> handleArrayStore(stack, instructions, JavaType.FLOAT, offset, blockId, op.code());
                case DASTORE -> handleArrayStore(stack, instructions, JavaType.DOUBLE, offset, blockId, op.code());
                case AASTORE -> handleArrayStore(stack, instructions,
                        JavaType.classType("java/lang/Object"), offset, blockId, op.code());

                // 类型检测/转换
                case CHECKCAST -> handleCheckCast(op, insn, stack, instructions, cp, offset, blockId);
                case INSTANCEOF -> handleInstanceOf(op, insn, stack, instructions, cp, offset, blockId);

                // 类型转换 —— 传入 op.code() 用于转换类型推断
                case I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D,
                     D2I, D2L, D2F, I2B, I2C, I2S -> handleConversion(op, stack, instructions, offset, blockId);

                // 监视器 —— 发射IR指令以便SynchronizedRecognizer能检测到
                case MONITORENTER -> {
                    Value obj = stack.isEmpty() ? ConstantValue.NULL : stack.pop();
                    instructions.add(new IrInstruction(nextId(), IrOpcode.MONITOR_ENTER,
                            JavaType.VOID, List.of(obj), offset, blockId, op.code(), null));
                }
                case MONITOREXIT -> {
                    Value obj = stack.isEmpty() ? ConstantValue.NULL : stack.pop();
                    instructions.add(new IrInstruction(nextId(), IrOpcode.MONITOR_EXIT,
                            JavaType.VOID, List.of(obj), offset, blockId, op.code(), null));
                }

                // 分支 —— 传入 op 用于比较运算符推断
                case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                     IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE,
                     IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE ->
                        handleCondition(op, stack, instructions, offset, blockId);
                case IFNULL, IFNONNULL -> handleNullCheck(op, stack, instructions, offset, blockId);
                case GOTO, GOTO_W -> {
                } // 控制流图处理这些
                case MULTIANEWARRAY -> handleMultiNewArray(insn, stack, instructions, cp, offset, blockId);
                case ATHROW -> instructions.add(new IrInstruction(nextId(), IrOpcode.THROW,
                        JavaType.VOID, List.of(stack.pop()), offset, blockId, op.code(), null));

                // Switch —— 弹出key,控制流图处理目标
                case TABLESWITCH, LOOKUPSWITCH -> {
                    if (!stack.isEmpty()) {
                        Value key = stack.pop();
                        instructions.add(new IrInstruction(nextId(), IrOpcode.SWITCH,
                                JavaType.VOID, List.of(key), offset, blockId, op.code(), null));
                    }
                }

                default -> {
                } // 跳过未知操作码
            }
        }

        return new FrameState(stack, locals);
    }

    /**
     * 处理POP/POP2操作码,从操作数栈弹出值.
     */
    private void handlePop(Opcode op, Deque<Value> stack) {
        if (stack.isEmpty()) {
            return;
        }
        stack.pop();
        if (op == Opcode.POP2 && !stack.isEmpty()) {
            stack.pop();
        }
    }

    // ── 常量 ───────────────────────────────────────────────────────

    /**
     * 处理DUP系列操作码,复制或交换操作数栈上的值.
     */
    private void handleDup(Opcode op, Deque<Value> stack) {
        switch (op) {
            case DUP -> {
                if (stack.isEmpty()) {
                    return;
                }
                Value v = stack.pop();
                stack.push(v);
                stack.push(v);
            }
            case DUP_X1 -> {
                if (stack.size() < 2) {
                    return;
                }
                Value v1 = stack.pop(), v2 = stack.pop();
                stack.push(v1);
                stack.push(v2);
                stack.push(v1);
            }
            case DUP_X2 -> {
                if (stack.size() < 3) {
                    return;
                }
                Value v1 = stack.pop(), v2 = stack.pop(), v3 = stack.pop();
                stack.push(v1);
                stack.push(v3);
                stack.push(v2);
                stack.push(v1);
            }
            case DUP2 -> {
                if (stack.isEmpty()) {
                    return;
                }
                Value v1 = stack.pop();
                if (isCategory2(v1)) {
                    // 栈顶值为long/double(类别2)——只复制该项
                    stack.push(v1);
                    stack.push(v1);
                } else if (!stack.isEmpty()) {
                    // 栈顶两个值均为类别1——复制两个
                    Value v2 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                } else {
                    stack.push(v1);
                    stack.push(v1);
                }
            }
            case SWAP -> {
                if (stack.size() < 2) {
                    return;
                }
                Value v1 = stack.pop(), v2 = stack.pop();
                stack.push(v1);
                stack.push(v2);
            }
        }
    }

    /**
     * 处理各种iconst/lconst/fconst/dconst和bipush/sipush常量加载.
     * 发射CONST IR指令使值能跨基本块边界通过InstructionRef链引用.
     */
    private void handleConstant(Opcode op, Instruction insn, Deque<Value> stack,
                                List<IrInstruction> instructions, int offset, int blockId) {
        ConstantValue cv = switch (op) {
            case ACONST_NULL -> ConstantValue.NULL;
            case ICONST_M1 -> new ConstantValue(-1, JavaType.INT);
            case ICONST_0 -> new ConstantValue(0, JavaType.INT);
            case ICONST_1 -> new ConstantValue(1, JavaType.INT);
            case ICONST_2 -> new ConstantValue(2, JavaType.INT);
            case ICONST_3 -> new ConstantValue(3, JavaType.INT);
            case ICONST_4 -> new ConstantValue(4, JavaType.INT);
            case ICONST_5 -> new ConstantValue(5, JavaType.INT);
            case LCONST_0 -> new ConstantValue(0L, JavaType.LONG);
            case LCONST_1 -> new ConstantValue(1L, JavaType.LONG);
            case FCONST_0 -> new ConstantValue(0.0f, JavaType.FLOAT);
            case FCONST_1 -> new ConstantValue(1.0f, JavaType.FLOAT);
            case FCONST_2 -> new ConstantValue(2.0f, JavaType.FLOAT);
            case DCONST_0 -> new ConstantValue(0.0, JavaType.DOUBLE);
            case DCONST_1 -> new ConstantValue(1.0, JavaType.DOUBLE);
            case BIPUSH -> {
                // 解码器以 readUnsignedByte 读入,BIPUSH 操作数是单字节
                // 有符号整数,须符号扩展(如 bipush -2 的 0xFE → -2 而非 254).
                int val = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
                yield new ConstantValue((byte) val, JavaType.INT);
            }
            case SIPUSH -> {
                // 解码器以 readUnsignedShort 读入,SIPUSH 操作数是 16 位
                // 有符号短整数,须符号扩展(如 sipush -5 → -5 而非 65531).
                int val = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
                yield new ConstantValue((short) val, JavaType.INT);
            }
            default -> null;
        };
        if (cv != null) {
            // 发射CONST IR指令使值能通过InstructionRef链跨基本块边界引用
            IrInstruction constInsn = new IrInstruction(nextId(), IrOpcode.CONST,
                    cv.type(), List.of(cv), offset, blockId);
            instructions.add(constInsn);
            constInsn.setResultValue(new InstructionRef(constInsn, cv.type()));
            stack.push(new InstructionRef(constInsn, cv.type()));
        }
    }

    // ── 加载 ──────────────────────────────────────────────────────

    /**
     * 处理LDC/LDC_W/LDC2_W常量池加载指令.
     * 发射CONST IR指令使值能通过InstructionRef链引用.
     */
    private void handleLdc(Instruction insn, Deque<Value> stack, ConstantPoolEntry[] cp,
                           List<IrInstruction> instructions, int offset, int blockId) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        Value value = null;
        if (cpIdx > 0 && cpIdx < cp.length) {
            ConstantPoolEntry entry = cp[cpIdx];
            if (entry instanceof ConstantPoolEntry.CpDynamic dyn) {
                // 动态常量(condy):识别标准引导方法并预解析为可渲染表达式
                value = BootstrapResolver.resolveCondy(dyn, cp, currentBootstrapMethods);
            } else {
                value = ConstantPoolResolver.cpValue(entry, cp);
            }
        }
        if (value == null) {
            value = new ConstantValue("?", JavaType.classType("java/lang/Object"));
        }
        // 发射CONST IR指令使值能通过InstructionRef链引用
        IrInstruction constInsn = new IrInstruction(nextId(), IrOpcode.CONST,
                value.type(), List.of(value), offset, blockId);
        instructions.add(constInsn);
        constInsn.setResultValue(new InstructionRef(constInsn, value.type()));
        stack.push(new InstructionRef(constInsn, value.type()));
    }

    /**
     * 处理局部变量加载指令(ILOAD,ALOAD等).
     * 确保变量携带其局部变量表名称,即使来自前驱帧状态的变量也如此.
     */
    private void handleLoad(Opcode op, Instruction insn, Deque<Value> stack,
                            Value[] locals, List<Variable> variables,
                            List<IrInstruction> instructions, int offset, int blockId) {
        int idx = varIndex(insn, op);
        JavaType type = loadType(op);
        Value v = locals[idx];
        if (v == null) {
            v = VariableFactory.lookupReadVar(variables, idx, type, offset, currentMethod);
        }
        // 确保变量携带其 LVT 名称(即使来自前驱帧状态).
        // 仅在变量完全未命名时设置(首次命名优先):
        // Variable 对象跨作用域共享,以使用处偏移重命名会把
        // 槽位复用后的前一个变量(如 res)错误改名为后一个变量(如 e).
        if (v instanceof Variable var) {
            if (var.name() == null) {
                String lvtName = currentMethod.lookupVarName(idx, offset);
                if (lvtName != null) {
                    var.setName(lvtName);
                }
            }
            if (var.genericType() == null) {
                String lvttSig = currentMethod.lookupVarTypeSignature(idx, offset, var.name());
                if (lvttSig != null) {
                    try {
                        JavaType gen = SignatureParser
                                .parseGenericType(lvttSig);
                        if (gen != null) {
                            var.setGenericType(gen);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        emitLoad(v, instructions, offset, blockId);
        stack.push(v);
    }

    // ── 存储 ──────────────────────────────────────────────────────

    /**
     * 根据加载操作码确定对应类型.
     */
    private JavaType loadType(Opcode op) {
        return switch (op) {
            case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3 -> JavaType.INT;
            case LLOAD, LLOAD_0, LLOAD_1, LLOAD_2, LLOAD_3 -> JavaType.LONG;
            case FLOAD, FLOAD_0, FLOAD_1, FLOAD_2, FLOAD_3 -> JavaType.FLOAT;
            case DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3 -> JavaType.DOUBLE;
            default -> JavaType.classType("java/lang/Object");
        };
    }

    // ── IINC ──────────────────────────────────────────────────────

    /**
     * 处理局部变量存储指令(ISTORE,ASTORE等).
     * 每次存储都创建一个新版本变量,防止槽位混淆(如"this"与重用槽位的临时变量).
     * 将新变量写入locals数组,确保后续LOAD指令找到Variable而非原始InstructionRef,
     * 从而防止错误的表达式展开.
     */
    private void handleStore(Opcode op, Instruction insn, Deque<Value> stack,
                             Value[] locals, List<Variable> variables,
                             List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        int idx = varIndex(insn, op);
        Value val = stack.pop();
        // 优先采用 LVT 声明的变量类型(修复"声明类型收窄":
        // Object o = new ArrayList() 此前被收窄为 ArrayList o).
        // 无 LVT 时回退到被存值类型.
        JavaType declaredType = VariableFactory.lookupDeclaredType(
                currentMethod, idx, offset, val.type());
        // JVM 将 boolean/char/byte/short 以 int 形式存于栈上,而 LVT 声明的
        // 是窄类型.按声明类型折叠常量实参(与 handleInvoke 的 boolean 参数
        // 折叠一致),否则渲染成 "boolean b = 1" / "char c = 59" 等非法或失真输出.
        val = foldStoreConstant(val, declaredType);
        // 每次存储都创建一个新版本——防止"this"槽位混淆
        Variable var = VariableFactory.createWriteVar(variables, idx, val.type(), offset, currentMethod);
        // 将新Variable存入locals数组确保后续LOAD找到Variable而非原始InstructionRef.
        // 这能防止错误的表达式展开,例如:
        // "n = cap - 1 | cap - 1 >>> 1" → "n = n | n >>> 1"(错误!)
        locals[idx] = var;
        instructions.add(IrInstruction.store(nextId(), var, val, offset, blockId));
    }

    /**
     * 处理IINC(整型递增)指令.
     * 读取当前值并写入新值——为写入创建新版本变量,
     * 并更新locals数组以确保同一块内后续LOAD看到新版本.
     */
    private void handleIinc(Instruction insn, List<Variable> variables,
                            List<IrInstruction> instructions, int offset, int blockId,
                            Value[] locals) {
        int idx = varIndex(insn, Opcode.IINC);
        int incr = insn.rawOperands().size() > 1 ? insn.rawOperands().get(1) : 0;
        // IINC读取当前值并写入新值——创建新版本变量
        Variable readVar = VariableFactory.lookupReadVar(variables, idx, JavaType.INT, offset, currentMethod);
        Variable writeVar = VariableFactory.createWriteVar(variables, idx, JavaType.INT, offset, currentMethod);
        // 更新locals数组以便同一块内后续LOAD看到新版本
        if (idx < locals.length) {
            locals[idx] = writeVar;
        }
        // 数值混淆消除(参照 CFR ControlFlowNumericObf):同块相邻 INC 合并.
        // x += 100; x -= 98 → x += 2——前一 INC 写入的变量版本即本次读取的版本
        //(连续版本),且两指令相邻同块时合并增量.
        // 跨块不合并:switch 贯穿中 case1 的 r+=1 与 case2 的 r+=2 在不同块,
        // 直接进入 case2 时只有 r+=2,合并成 r+=3 会语义错误.
        if (!instructions.isEmpty()) {
            IrInstruction prev = instructions.get(instructions.size() - 1);
            if (prev.opcode() == IrOpcode.INC && prev.blockId() == blockId
                    && prev.operands().size() >= 3
                    && prev.operands().get(1) instanceof Variable pv
                    && pv.slot() == readVar.slot() && pv.version() == readVar.version()
                    && prev.operands().get(2) instanceof ConstantValue pcv
                    && pcv.value() instanceof Number pn) {
                int combined = pn.intValue() + incr;
                instructions.set(instructions.size() - 1,
                        new IrInstruction(prev.id(), IrOpcode.INC, JavaType.INT,
                                List.of(prev.operands().get(0), writeVar,
                                        new ConstantValue(combined, JavaType.INT)),
                                prev.sourceOffset(), prev.blockId()));
                return; // 已合并进前一条,不新增
            }
        }
        instructions.add(new IrInstruction(nextId(), IrOpcode.INC, JavaType.INT,
                List.of(readVar, writeVar, new ConstantValue(incr, JavaType.INT)),
                offset, blockId));
    }

    // ── 算术 ─────────────────────────────────────────────────────

    /**
     * 处理算术二元运算(IADD,ISUB等).
     * 弹出右操作数和左操作数(栈顶为右),创建BINARY IR指令并压回结果.
     */
    private void handleArithmetic(Opcode op, Deque<Value> stack,
                                  List<IrInstruction> instructions,
                                  JavaType type, int offset, int blockId) {
        if (stack.size() < 2) {
            return;
        }
        Value right = stack.pop();
        Value left = stack.pop();
        IrInstruction bin = IrInstruction.binary(nextId(), IrOpcode.BINARY, left, right,
                type, offset, blockId, op.code());
        instructions.add(bin);
        bin.setResultValue(new InstructionRef(bin, type));
        stack.push(new InstructionRef(bin, type));
    }

    /**
     * 处理取负操作(INEG,LNEG等一元运算).
     */
    private void handleNegate(Opcode op, Deque<Value> stack,
                              List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        IrInstruction un = new IrInstruction(nextId(), IrOpcode.UNARY, v.type(), List.of(v),
                offset, blockId, op.code(), null);
        instructions.add(un);
        un.setResultValue(new InstructionRef(un, v.type()));
        stack.push(new InstructionRef(un, v.type()));
    }

    // ── 比较 ──────────────────────────────────────────────────────

    /**
     * 处理比较操作(lcmp,fcmpl,fcmpg,dcmpl,dcmpg).
     * 创建COMPARE IR指令,结果类型为int.
     */
    private void handleComparison(Opcode op, Deque<Value> stack,
                                  List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.size() < 2) {
            return;
        }
        Value right = stack.pop();
        Value left = stack.pop();
        IrInstruction cmp = IrInstruction.binary(nextId(), IrOpcode.COMPARE, left, right,
                JavaType.INT, offset, blockId, op.code());
        instructions.add(cmp);
        cmp.setResultValue(new InstructionRef(cmp, JavaType.INT));
        stack.push(new InstructionRef(cmp, JavaType.INT));
    }

    // ── 字段 ─────────────────────────────────────────────────────

    /**
     * 处理字段加载(GETFIELD/GETSTATIC).
     * 解析字段名和类型,创建FIELD_LOAD IR指令.
     * 对于GETSTATIC,标记声明类信息以便BlockReducer输出完整限定名.
     */
    private void handleFieldLoad(Opcode op, Instruction insn, Deque<Value> stack,
                                 List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                 int offset, int blockId) {
        Value obj = (op == Opcode.GETFIELD && !stack.isEmpty()) ? stack.pop() : null;
        String fieldName = ConstantPoolResolver.resolveFieldName(insn, cp);
        JavaType fieldType = ConstantPoolResolver.resolveFieldType(insn, cp);
        IrInstruction fi = IrInstruction.fieldLoad(nextId(), obj,
                fieldType, offset, blockId, fieldName);
        instructions.add(fi);
        fi.setResultValue(new InstructionRef(fi, fi.resultType()));

        // 对于静态字段访问(GETSTATIC),标记声明类信息,
        // 以便BlockReducer输出System.out而非仅out
        if (op == Opcode.GETSTATIC) {
            String declaringClass = ConstantPoolResolver.resolveFieldDeclaringClass(insn, cp);
            if (declaringClass != null) {
                fi.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.DECLARING_CLASS,
                        SemanticAnnotation.KEY_DECLARING_CLASS,
                        declaringClass));
            }
        }

        stack.push(new InstructionRef(fi, fi.resultType()));
    }

    /**
     * 处理字段存储(PUTFIELD/PUTSTATIC).
     * 解析字段名,创建FIELD_STORE IR指令.
     */
    private void handleFieldStore(Opcode op, Instruction insn, Deque<Value> stack,
                                  List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                  int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value val = stack.pop();
        Value obj = (op == Opcode.PUTFIELD && !stack.isEmpty()) ? stack.pop() : null;
        String fieldName = ConstantPoolResolver.resolveFieldName(insn, cp);
        // 布尔字段赋值 0/1 常量 → boolean 字面量(与 handleInvoke 的 boolean 参数折叠一致).
        // JVM 将 boolean 表示为其栈上的 int(ICONST_0/1),不重标类型会渲染成非法的 "this.e = 1".
        JavaType fieldType = ConstantPoolResolver.resolveFieldType(insn, cp);
        if (fieldType != null && fieldType.kind() == TypeKind.BOOLEAN) {
            ConstantValue cv = unwrapConstant(val);
            if (cv != null && cv.value() instanceof Integer i) {
                val = new ConstantValue(i != 0, JavaType.BOOLEAN);
            }
        }
        instructions.add(IrInstruction.fieldStore(nextId(), obj, val, offset, blockId, fieldName));
    }

    /**
     * 处理常规方法调用(INVOKEVIRTUAL/INVOKESPECIAL/INVOKESTATIC/INVOKEINTERFACE).
     * 从常量池解析参数类型和返回类型,按逆序弹出实参,弹出接收者对象,
     * 创建INVOKE IR指令.对boolean参数折叠0/1常量,对静态调用标记声明类.
     */
    private void handleInvoke(Opcode op, Instruction insn, Deque<Value> stack,
                              List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                              int offset, int blockId) {
        pendingGenericParams = null;
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        int argCount = 0;
        JavaType returnType = JavaType.classType("java/lang/Object");
        String methodName = null;
        String declaringClass = null; // 用于构造函数委托目标
        String methodDesc = null;     // 方法描述符(自类签名查找用)
        JavaType[] paramTypes = null; // 用于boolean折叠

        if (cpIdx > 0 && cpIdx < cp.length) {
            try {
                ConstantPoolEntry cpEntry = cp[cpIdx];
                int natIdx = -1;
                int classIdx = -1;
                switch (cpEntry) {
                    case ConstantPoolEntry.CpMethodRef ref -> {
                        natIdx = ref.nameAndTypeIndex();
                        classIdx = ref.classIndex();
                    }
                    case ConstantPoolEntry.CpInterfaceMethodRef ref -> {
                        natIdx = ref.nameAndTypeIndex();
                        classIdx = ref.classIndex();
                    }
                    default -> {
                    }
                }
                if (classIdx > 0 && classIdx < cp.length) {
                    declaringClass = ConstantPoolParser.className(cp, classIdx);
                }
                if (natIdx > 0 && natIdx < cp.length && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType(
                        int nameIndex, int descriptorIndex
                )) {
                    String desc = ConstantPoolParser.utf8(cp, descriptorIndex);
                    methodDesc = desc;
                    methodName = ConstantPoolParser.utf8(cp, nameIndex);
                    paramTypes = TypeResolver.parseMethodParameterTypes(desc);
                    argCount = paramTypes.length;
                    returnType = TypeResolver.parseMethodReturnType(desc);
                }
            } catch (Exception ignored) {
                // 保持默认值
            }
        }

        // 按逆序弹出实参
        List<Value> args = new ArrayList<>();
        for (int a = 0; a < argCount && !stack.isEmpty(); a++) {
            args.addFirst(stack.pop());
        }
        // 捕获接收者(对于非静态调用)——保留方法调用的目标对象
        Value receiver = null;
        if (op != Opcode.INVOKESTATIC && !stack.isEmpty()) {
            receiver = stack.pop();
        }

        // 折叠boolean常量:当参数为boolean时,将0/1转为false/true.
        // 由于常量现在以CONST IR的形式发射,需沿InstructionRef链追溯.
        if (paramTypes != null) {
            for (int p = 0; p < paramTypes.length && p < args.size(); p++) {
                if (paramTypes[p].kind() == TypeKind.BOOLEAN) {
                    Value arg = args.get(p);
                    ConstantValue cv = unwrapConstant(arg);
                    if (cv != null && cv.value() instanceof Integer i) {
                        args.set(p, new ConstantValue(i != 0, JavaType.BOOLEAN));
                    }
                } else if (paramTypes[p].kind() == TypeKind.CHAR) {
                    // char 参数折叠:常量实参(如 append(';') 的 bipush 59,
                    // CONST 值为 Byte)重标为 char 字面量.否则渲染为 int 59,
                    // 在字符串上下文中输出 "59" 两字符而非 ";"(行为差异).
                    Value arg = args.get(p);
                    ConstantValue cv = unwrapConstant(arg);
                    if (cv != null && cv.value() instanceof Number n
                            && n.intValue() >= 0 && n.intValue() <= 0xFFFF) {
                        args.set(p, new ConstantValue((char) n.intValue(), JavaType.CHAR));
                    }
                } else if (paramTypes[p].kind() == TypeKind.BYTE
                        || paramTypes[p].kind() == TypeKind.SHORT) {
                    // byte/short 参数折叠:常量整型实参(如 calc((byte)10) 的
                    // bipush 10)重标为 byte/short——否则渲染为 int 10,传给
                    // byte/short 参数时重编译报"有损转换".
                    Value arg = args.get(p);
                    ConstantValue cv = unwrapConstant(arg);
                    if (cv != null && cv.value() instanceof Number n
                            && n.intValue() >= Byte.MIN_VALUE && n.intValue() <= 0xFFFF) {
                        args.set(p, new ConstantValue(n.intValue(),
                                paramTypes[p].kind() == TypeKind.BYTE
                                        ? JavaType.BYTE : JavaType.SHORT));
                    }
                }
            }
        }

        // 从调用点实参 + 方法泛型签名推断返回类型的泛型参数
        // (反射取 java.* 方法签名,绑定类型变量;如 Map.of("a",1) → Map<String,Integer>)
        if (op != Opcode.INVOKESTATIC && receiver != null) {
            // 实例方法:接收者的类型实参替换类类型变量
            // (如 List<String> l 的 l.get(0) → String,供冗余强转抑制)
            JavaType recvType = receiver instanceof Variable rv && rv.genericType() != null
                    ? rv.genericType() : receiver.type();
            returnType = GenericMethodResolver.inferInstanceReturnType(declaringClass,
                    methodName, recvType, returnType, paramTypes, args);
            // 声明类方法:被反编译的类自身方法(如 Cache<K,V> 接口的 get)或
            // 上下文可加载的类(如匿名类的外层 ArrayMap)的泛型签名在 ClassFileModel
            // 中可得,反射路径不覆盖用户类——据此把 this.get(key) 返回类型还原为 V
            // (否则擦除为 Object).仅实例调用(静态方法无接收者,类类型变量不适用,
            // 且 receiver==null 时取 receiver.type() 会 NPE 损坏方法 IR).
            if (declaringClass != null && methodName != null && methodDesc != null) {
                SelfMethodSig sig = findDeclaredMethodSignature(
                        declaringClass, methodName, methodDesc);
                if (sig != null) {
                    // 仅当前类的方法签名含类类型参数(跨类加载的 ArrayMap 签名
                    // 无此上下文,类类型变量不参与返回推断,参数强转不受影响)
                    String classSig = declaringClass.equals(selfClass != null
                            ? selfClass.internalName() : null)
                            ? selfClass.signature() : null;
                    JavaType sigRet = GenericMethodResolver.inferFromSignature(
                            sig.paramTypes(), sig.returnType(),
                            classTypeParams(classSig),
                            recvType, returnType, args);
                    if (sigRet != null) {
                        returnType = sigRet;
                    }
                    // 记录泛型参数类型,供 ExprTranslator 对擦除为 Object 的
                    // 实参插入 (K)/(V) 强转(如 readObject 的 put(key,value)).
                    pendingGenericParams = java.util.Arrays.asList(sig.paramTypes());
                }
            }
        } else {
            returnType = GenericMethodResolver.inferGenericReturnType(declaringClass, methodName,
                    returnType, paramTypes, args);
        }

        IrInstruction inv = IrInstruction.invoke(nextId(), receiver, args, returnType,
                offset, blockId, methodName);
        if (pendingGenericParams != null) {
            inv.setGenericParamTypes(pendingGenericParams);
        }
        instructions.add(inv);

        // 对静态调用标记声明类(用于Arrays.fill() vs fill()的区分)
        if (op == Opcode.INVOKESTATIC && declaringClass != null) {
            inv.addAnnotation(SemanticAnnotation.of(
                    SemanticTag.DECLARING_CLASS,
                    SemanticAnnotation.KEY_DECLARING_CLASS,
                    declaringClass));
        }

        // 标记构造函数委托调用,附带目标类信息
        if ("<init>".equals(methodName)) {
            if (declaringClass != null) {
                inv.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.CONSTRUCTOR_DELEGATION,
                        SemanticAnnotation.KEY_TARGET_CLASS,
                        declaringClass));
            } else {
                inv.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.CONSTRUCTOR_DELEGATION));
            }
        }

        if (returnType.kind() != TypeKind.VOID) {
            inv.setResultValue(new InstructionRef(inv, returnType));
            stack.push(new InstructionRef(inv, returnType));
        }
    }

    // ── InvokeDynamic ─────────────────────────────────────────────

    /**
     * 处理INVOKEDYNAMIC指令.
     * 解析引导方法信息,创建INVOKE IR指令,并标记INDY语义注解.
     * 引导方法参数用于下游pass检测lambda表达式和方法引用.
     */
    private void handleInvokeDynamic(Instruction insn, Deque<Value> stack,
                                     List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                     int offset, int blockId) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        int argCount = 0;
        JavaType returnType = JavaType.classType("java/lang/Object");
        String methodName = "invokeDynamic";
        String descriptor = "";
        int bootstrapIdx = -1;

        if (cpIdx > 0 && cpIdx < cp.length) {
            try {
                ConstantPoolEntry entry = cp[cpIdx];
                if (entry instanceof ConstantPoolEntry.CpInvokeDynamic(int bootstrapMethodAttrIndex, int natIdx)) {
                    bootstrapIdx = bootstrapMethodAttrIndex;
                    if (natIdx > 0 && natIdx < cp.length
                            && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType(
                            int nameIndex, int descriptorIndex
                    )) {
                        descriptor = ConstantPoolParser.utf8(cp, descriptorIndex);
                        methodName = ConstantPoolParser.utf8(cp, nameIndex);
                        var params = TypeResolver.parseMethodParameterTypes(descriptor);
                        argCount = params.length;
                        returnType = TypeResolver.parseMethodReturnType(descriptor);
                    }
                }
            } catch (Exception ignored) {
                // 保持默认值
            }
        }

        // 弹出实参
        List<Value> args = new ArrayList<>();
        for (int a = 0; a < argCount && !stack.isEmpty(); a++) {
            args.addFirst(stack.pop());
        }

        IrInstruction inv = IrInstruction.invoke(nextId(), null, args, returnType,
                offset, blockId, methodName);

        // 解析引导方法信息,供下游检测lambda vs 方法引用
        java.util.Map<String, Object> annotProps = new java.util.LinkedHashMap<>();
        annotProps.put("bootstrapIdx", bootstrapIdx);
        annotProps.put("indyName", methodName);
        annotProps.put("descriptor", descriptor);

        if (bootstrapIdx >= 0 && !currentBootstrapMethods.isEmpty()
                && bootstrapIdx < currentBootstrapMethods.size()) {
            try {
                BootstrapMethodEntry bsm = currentBootstrapMethods.get(bootstrapIdx);
                BootstrapResolver.resolveBootstrapMethod(bsm, cp, annotProps);
            } catch (Exception ignored) {
                // 引导方法解析为尽力而为
            }
        }

        // 使用实现方法描述符重建带泛型参数的函数式接口类型.
        // 注意:samDescriptor 来自 MethodType 常量,类型的泛型参数被擦除.
        // implDescriptor 来自实现方法引用,保留正确的泛型参数.
        String implDesc = (String) annotProps.get("implDescriptor");
        if (implDesc != null && !implDesc.isEmpty()
                && returnType.kind() == TypeKind.CLASS) {
            returnType = BootstrapResolver.buildGenericFunctionalType(returnType, implDesc);
        }

        inv.addAnnotation(SemanticAnnotation.of(
                SemanticTag.INDY, annotProps));
        instructions.add(inv);
        if (returnType.kind() != TypeKind.VOID) {
            inv.setResultValue(new InstructionRef(inv, returnType));
            stack.push(new InstructionRef(inv, returnType));
        }
    }

    // ── 对象 / 数组 ───────────────────────────────────────────────

    /**
     * 处理NEW对象创建指令.
     * 从常量池解析类名,创建NEW IR指令.
     */
    private void handleNew(Instruction insn, Deque<Value> stack,
                           List<IrInstruction> instructions,
                           ConstantPoolEntry[] cp, int offset, int blockId) {
        // 从常量池解析类类型
        String className = "java/lang/Object";
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        if (cpIdx > 0 && cpIdx < cp.length) {
            className = ConstantPoolParser.className(cp, cpIdx);
            if (className == null) {
                className = "java/lang/Object";
            }
        }
        IrInstruction n = IrInstruction.newInsn(nextId(),
                JavaType.classType(className), offset, blockId);
        instructions.add(n);
        n.setResultValue(new InstructionRef(n, n.resultType()));
        stack.push(new InstructionRef(n, n.resultType()));
    }

    /**
     * 处理NEWARRAY基本类型数组创建指令(new int[10],new byte[20]等).
     * 操作数字节表示数组类型代码(4=boolean, 5=char, 6=float, 7=double,
     * 8=byte, 9=short, 10=int, 11=long).
     */
    private void handleNewPrimitiveArray(Instruction insn, Deque<Value> stack,
                                         List<IrInstruction> instructions,
                                         int offset, int blockId) {
        int typeCode = insn.rawOperands().isEmpty() ? 10 : insn.rawOperands().get(0);
        JavaType elementType = switch (typeCode) {
            case 4 -> JavaType.BOOLEAN;
            case 5 -> JavaType.CHAR;
            case 6 -> JavaType.FLOAT;
            case 7 -> JavaType.DOUBLE;
            case 8 -> JavaType.BYTE;
            case 9 -> JavaType.SHORT;
            case 11 -> JavaType.LONG;
            default -> JavaType.INT; // 10 = T_INT
        };
        Value size = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
        JavaType arrayType = JavaType.array(elementType, 1);
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                arrayType, List.of(size), offset, blockId, insn.opcode(), null);
        instructions.add(na);
        na.setResultValue(new InstructionRef(na, arrayType));
        stack.push(new InstructionRef(na, arrayType));
    }

    /**
     * 处理ANEWARRAY引用类型数组创建指令(new String[10]).
     */
    private void handleNewArray(Opcode op, Instruction insn, Deque<Value> stack,
                                List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                int offset, int blockId) {
        Value size = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
        JavaType elementType = ConstantPoolResolver.resolveClassType(insn, cp);
        JavaType arrayType = JavaType.array(elementType, 1);
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                arrayType, List.of(size), offset, blockId, op.code(), null);
        instructions.add(na);
        na.setResultValue(new InstructionRef(na, arrayType));
        stack.push(new InstructionRef(na, arrayType));
    }

    /**
     * 处理MULTIANEWARRAY多维数组创建指令.
     */
    private void handleMultiNewArray(Instruction insn, Deque<Value> stack,
                                     List<IrInstruction> instructions,
                                     ConstantPoolEntry[] cp,
                                     int offset, int blockId) {
        // MULTIANEWARRAY操作数格式:[cp_index, dimensions]
        int dims = 1;
        if (insn.rawOperands().size() > 1) {
            dims = insn.rawOperands().get(1);
        }
        // 解析数组类型:常量池索引指向数组描述符(如 "[[I" 表示 int[][]),
        // 保留元素类型(修复 new int[2][4] 被降级为 new Object[2][4] 的问题).
        JavaType arrayType = JavaType.classType("java/lang/Object");
        if (!insn.rawOperands().isEmpty()) {
            int cpIdx = insn.rawOperands().get(0);
            if (cpIdx > 0 && cpIdx < cp.length) {
                String desc = ConstantPoolParser.className(cp, cpIdx);
                if (desc != null && !desc.startsWith("<")) {
                    int rank = 0;
                    while (rank < desc.length() && desc.charAt(rank) == '[') {
                        rank++;
                    }
                    if (rank > 0) {
                        JavaType base = TypeResolver
                                .parseFieldDescriptor(desc.substring(rank));
                        arrayType = JavaType.array(base, rank);
                    }
                }
            }
        }
        // 从栈中弹出各维度大小
        List<Value> sizes = new ArrayList<>();
        for (int d = 0; d < dims && !stack.isEmpty(); d++) {
            sizes.addFirst(stack.pop());
        }
        IrInstruction na = new IrInstruction(nextId(), IrOpcode.NEW_ARRAY,
                arrayType, sizes, offset, blockId);
        instructions.add(na);
        na.setResultValue(new InstructionRef(na, na.resultType()));
        stack.push(new InstructionRef(na, na.resultType()));
    }

    /**
     * 处理数组元素加载(IALOAD,AALOAD等).
     * 弹出索引和数组引用,创建ARRAY_LOAD IR指令.
     */
    private void handleArrayLoad(Deque<Value> stack, List<IrInstruction> instructions,
                                 JavaType elementType, int offset, int blockId, int opcode) {
        if (stack.size() < 2) {
            return;
        }
        Value index = stack.pop();
        Value arr = stack.pop();
        // AALOAD 的元素类型取决于数组操作数的静态类型:引用数组(如 int[][])
        // 的组件是 int[](而非 Object).当 AALOAD(元素类型被硬编码为
        // java/lang/Object)且 arr 携带数组类型时,取其 elementOf 作为真实
        // 组件类型,避免下游把 int[] row 推断成 Object 而破坏嵌套 for-each.
        JavaType effectiveElement = elementType;
        if (elementType.kind() == TypeKind.CLASS
                && "java/lang/Object".equals(elementType.internalName())
                && arr.type().kind() == TypeKind.ARRAY) {
            effectiveElement = JavaType.elementOf(arr.type());
        }
        IrInstruction al = new IrInstruction(nextId(), IrOpcode.ARRAY_LOAD,
                effectiveElement, List.of(arr, index), offset, blockId, opcode, null);
        instructions.add(al);
        al.setResultValue(new InstructionRef(al, effectiveElement));
        stack.push(new InstructionRef(al, effectiveElement));
    }

    // ── 数组元素加载/存储 ────────────────────────────────────────

    /**
     * 处理数组元素存储(IASTORE,AASTORE等).
     * 弹出值,索引和数组引用,创建ARRAY_STORE IR指令.
     */
    private void handleArrayStore(Deque<Value> stack, List<IrInstruction> instructions,
                                  JavaType elementType, int offset, int blockId, int opcode) {
        if (stack.size() < 3) {
            return;
        }
        Value value = stack.pop();
        Value index = stack.pop();
        Value arr = stack.pop();
        IrInstruction ast = new IrInstruction(nextId(), IrOpcode.ARRAY_STORE,
                JavaType.VOID, List.of(arr, index, value), offset, blockId, opcode, null);
        instructions.add(ast);
    }

    /**
     * 处理ARRAYLENGTH数组长度获取指令.
     */
    private void handleArrayLength(Deque<Value> stack, List<IrInstruction> instructions,
                                   int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value arr = stack.pop();
        IrInstruction al = new IrInstruction(nextId(), IrOpcode.ARRAY_LENGTH,
                JavaType.INT, List.of(arr), offset, blockId);
        instructions.add(al);
        al.setResultValue(new InstructionRef(al, JavaType.INT));
        stack.push(new InstructionRef(al, JavaType.INT));
    }

    // ── 类型 / 转换 ───────────────────────────────────────────────

    /**
     * 处理CHECKCAST类型检查转换指令.
     */
    private void handleCheckCast(Opcode op, Instruction insn, Deque<Value> stack,
                                 List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                 int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        // 从常量池解析目标类型
        JavaType targetType = ConstantPoolResolver.resolveClassType(insn, cp);
        IrInstruction c = IrInstruction.cast(nextId(), v, targetType, offset, blockId, op.code());
        instructions.add(c);
        c.setResultValue(new InstructionRef(c, c.resultType()));
        stack.push(new InstructionRef(c, c.resultType()));
    }

    /**
     * 处理INSTANCEOF类型检测指令.
     * nameHint携带目标类内部名,供BlockReducer使用.
     */
    private void handleInstanceOf(Opcode op, Instruction insn, Deque<Value> stack,
                                  List<IrInstruction> instructions, ConstantPoolEntry[] cp,
                                  int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value obj = stack.pop(); // 保留对象作为操作数
        JavaType targetType = ConstantPoolResolver.resolveClassType(insn, cp);
        IrInstruction io = new IrInstruction(nextId(), IrOpcode.INSTANCE_OF,
                JavaType.INT, List.of(obj), offset, blockId, op.code(), targetType.internalName());
        io.setResultValue(new InstructionRef(io, JavaType.INT));
        instructions.add(io);
        stack.push(new InstructionRef(io, JavaType.INT));
    }

    /**
     * 处理类型转换指令(I2L,F2I等).
     */
    private void handleConversion(Opcode op, Deque<Value> stack,
                                  List<IrInstruction> instructions, int offset, int blockId) {
        if (stack.isEmpty()) {
            return;
        }
        Value v = stack.pop();
        JavaType to = targetType(op);
        IrInstruction conv = IrInstruction.cast(nextId(), v, to, offset, blockId, op.code());
        instructions.add(conv);
        conv.setResultValue(new InstructionRef(conv, to));
        stack.push(new InstructionRef(conv, to));
    }

    // ── 分支 ──────────────────────────────────────────────────────

    /**
     * 处理条件分支指令(if系列).
     * 区分零值比较(IFEQ..IFLE:单操作数与0比较)
     * 和整数比较(IF_ICMPxx:双操作数比较).
     * 对于IFxx系列,栈上有且仅有一个值,将其作为左操作数与0比较.
     * 这样IFEQ生成的比较就正确地表现为 "capacity > 0".
     */
    private void handleCondition(Opcode op, Deque<Value> stack,
                                 List<IrInstruction> instructions, int offset, int blockId) {
        boolean isIfCmp = switch (op) {
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                 IF_ACMPEQ, IF_ACMPNE -> true;
            default -> false;
        };
        if (isIfCmp) {
            // 双操作数弹出:先右后左(标准JVM语义)
            Value right = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            Value left = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                    JavaType.INT, List.of(left, right), offset, blockId, op.code(), null));
        } else {
            // IFxx —— 单操作数:栈上的值与0比较.
            // 将值放在左边,0放在右边,使IFGT等产生正确的 "capacity > 0" 语义
            Value val = !stack.isEmpty() ? stack.pop() : new ConstantValue(0, JavaType.INT);
            instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                    JavaType.INT, List.of(val, new ConstantValue(0, JavaType.INT)),
                    offset, blockId, op.code(), null));
        }
    }

    /**
     * 处理空值检查指令(IFNULL/IFNONNULL).
     */
    private void handleNullCheck(Opcode op, Deque<Value> stack, List<IrInstruction> instructions,
                                 int offset, int blockId) {
        Value ref = !stack.isEmpty() ? stack.pop() : ConstantValue.NULL;
        instructions.add(new IrInstruction(nextId(), IrOpcode.CONDITION,
                JavaType.INT, List.of(ref, ConstantValue.NULL), offset, blockId, op.code(), null));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  共享辅助方法
    // ═══════════════════════════════════════════════════════════════════

    /** 获取下一条指令的唯一ID. */
    private int nextId() {return nextInsnId++;}

    /**
     * 获取指令操作的变量索引.
     * 优先使用指令级别varIndex(由WIDE或显式操作数解码设置),
     * 其次使用操作码隐式索引(例如 iload_0 -> 0),最后使用首个原始操作数.
     */
    private int varIndex(Instruction insn, Opcode op) {
        if (insn.varIndex() >= 0 && insn.opcode() == op.code()) {
            return insn.varIndex();
        }
        // 仅对无显式操作数的操作码使用隐式变量索引
        // (例如 ILOAD_0 -> 0, ISTORE_3 -> 3).
        // 对于带显式索引的操作码(如含操作数字节的ILOAD),implicitVarIndex为0但这是错误的.
        if (op.implicitVarIndex() >= 0 && op.operandBytes() == 0) {
            return op.implicitVarIndex();
        }
        if (!insn.rawOperands().isEmpty()) {
            return insn.rawOperands().get(0);
        }
        return 0;
    }

    /**
     * 发射LOAD IR指令(如果值是Variable类型).
     */
    private void emitLoad(Value v, List<IrInstruction> instructions, int offset, int blockId) {
        if (v instanceof Variable var) {
            IrInstruction load = IrInstruction.load(nextId(), var, offset, blockId);
            instructions.add(load);
            load.setResultValue(new InstructionRef(load, v.type()));
        }
    }

    /**
     * 根据类型转换操作码确定目标类型.
     */
    private JavaType targetType(Opcode op) {
        return switch (op) {
            case I2L -> JavaType.LONG;
            case I2F -> JavaType.FLOAT;
            case I2D -> JavaType.DOUBLE;
            case L2I -> JavaType.INT;
            case L2F -> JavaType.FLOAT;
            case L2D -> JavaType.DOUBLE;
            case F2I -> JavaType.INT;
            case F2L -> JavaType.LONG;
            case F2D -> JavaType.DOUBLE;
            case D2I -> JavaType.INT;
            case D2L -> JavaType.LONG;
            case D2F -> JavaType.FLOAT;
            case I2B -> JavaType.BYTE;
            case I2C -> JavaType.CHAR;
            case I2S -> JavaType.SHORT;
            default -> JavaType.INT;
        };
    }

    /** 自类方法的泛型签名:参数类型与返回类型(含类型变量). */
    private record SelfMethodSig(JavaType[] paramTypes, JavaType returnType) {
    }
}
