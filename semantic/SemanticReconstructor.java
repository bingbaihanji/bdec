package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.LinearIr;

/**
 * 语义重建管线的编排器.
 *
 * <p>在 LinearIr 上运行一系列遍历(pass),从原始字节码 IR 中恢复高级 Java 语义.
 * 各遍历在不动点循环中反复执行,直到不再发生变化(最多 10 轮迭代).
 *
 * <p>这一层是区分 BDEC 是"IR 美化打印器"还是真正反编译器的关键所在.
 *
 * <p>管线执行顺序(对应 CFR 的 Op03Rewriters + Procyon 的 AstOptimizer):
 * <ol>
 *   <li>定义-使用表达式合并(内联单次使用的临时变量)</li>
 *   <li>类型感知常量折叠(0/1 → false/true)</li>
 *   <li>构造函数展开(this()/super() 折叠)</li>
 *   <li>RequireNonNull 消除</li>
 *   <li>synchronized 块识别</li>
 * </ol>
 */
public final class SemanticReconstructor {

    /** 类型感知常量折叠器 */
    private final TypeAwareConstantFolder typeAwareConstantFolder
            = new TypeAwareConstantFolder();

    /** 构造函数委托展开器 */
    private final ConstructorExpander constructorExpander
            = new ConstructorExpander();

    /** RequireNonNull 空检查消除器 */
    private final RequireNonNullEliminator requireNonNullEliminator
            = new RequireNonNullEliminator();

    /** synchronized 块识别器 */
    private final SynchronizedRecognizer synchronizedRecognizer
            = new SynchronizedRecognizer();

    /** 定义-使用表达式合并器 */
    private final DefUseExpressionMerger defUseExpressionMerger
            = new DefUseExpressionMerger();

    /**
     * 对方法的 IR 执行全部语义重建遍历.
     *
     * @param ir        待重建的线性 IR
     * @param method    方法模型(提供类型信息,构造函数检测)
     * @param cfg       控制流图(用于异常处理器分析)
     * @param classFile 类文件模型(提供父类名称等信息)
     * @return 重建后的 IR(若无变更则可能返回同一对象)
     */
    public LinearIr reconstruct(LinearIr ir, MethodModel method,
                                ControlFlowGraph cfg, ClassFileModel classFile) {
        int maxIterations = 10;
        boolean changed = true;

        while (changed && maxIterations-- > 0) {
            changed = false;

            // 1. 定义-使用表达式合并(最先执行,使后续遍历更有效)
            changed |= defUseExpressionMerger.merge(ir);

            // 2. 类型感知常量折叠
            changed |= typeAwareConstantFolder.fold(ir, method);

            // 3. 构造函数展开
            changed |= constructorExpander.expand(ir, method, classFile);

            // 4. RequireNonNull 消除
            changed |= requireNonNullEliminator.eliminate(ir);

            // 5. synchronized 块识别
            changed |= synchronizedRecognizer.recognize(ir, cfg);
        }

        return ir;
    }
}
