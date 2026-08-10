package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ast.AstBuilder;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.rewrite.*;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.decompiler.Decompiler;
import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticListener;
import com.bingbaihanji.bdec.emit.SourceEmitter;
import com.bingbaihanji.bdec.emit.SourceFile;
import com.bingbaihanji.bdec.ir.CopyPropagation;
import com.bingbaihanji.bdec.ir.DeadCodeElimination;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.SsaBuilder;
import com.bingbaihanji.bdec.ir.SsaForm;
import com.bingbaihanji.bdec.ir.TypeInference;
import com.bingbaihanji.bdec.semantic.SemanticReconstructor;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BDEC 反编译引擎核心类,实现 {@link Decompiler} 接口.
 *
 * <p>负责协调反编译的各个阶段:解析 class 文件,构建控制流图(CFG),
 * 生成线性中间表示(LinearIr),SSA(静态单赋值)优化,结构化控制流,
 * 构建抽象语法树(AST),AST 重写以及源代码输出.</p>
 */
public class BdecEngine implements Decompiler {

    /** 反编译配置 */
    private final BdecConfig config;

    /** 诊断信息监听器 */
    private final DiagnosticListener diagnostics;

    /** Class 文件字节码读取器 */
    private final ClassFileReader classReader = new ClassFileReader();

    /** 控制流图构建器 */
    private final CfgBuilder cfgBuilder = new CfgBuilder();

    /** 中间表示(IR)构建器 */
    private final IrBuilder irBuilder = new IrBuilder();

    /** 控制流结构化器,将 IR 转为结构化方法 */
    private final ControlFlowStructurer structurer = new ControlFlowStructurer();

    /** SSA(静态单赋值)构建器 */
    private final SsaBuilder ssaBuilder = new SsaBuilder();

    /** 类型推断器 */
    private final TypeInference typeInference = new TypeInference();

    /** 复制传播优化器 */
    private final CopyPropagation copyPropagation = new CopyPropagation();

    /** 死代码消除器 */
    private final DeadCodeElimination dce = new DeadCodeElimination();

    /** 语义重建器,负责恢复高级语义信息 */
    private final SemanticReconstructor semanticReconstructor = new SemanticReconstructor();

    /** 抽象语法树(AST)构建器 */
    private final AstBuilder astBuilder = new AstBuilder();

    /** AST 重写器,包含多个重写规则,按顺序应用 */
    private final AstRewriter astRewriter = new AstRewriter(
            List.of(new RecordRewriter(), new SealedClassRewriter(),
                    new LambdaRewriter(), new MethodRefRewriter(),
                    new StringConcatRewriter(), new TextBlockRewriter(),
                    new ForEachRewriter(), new TryResourceRewriter(),
                    new SwitchExprRewriter(), new PatternMatchRewriter(),
                    new TernaryRewriter(), new BoxingRewriter(),
                    new StringSwitchRewriter(),
                    new EnumSwitchRewriter(),
                    new EnumRewriter(),
                    new SourceCleanup()));

    /** 源代码输出器 */
    private final SourceEmitter sourceEmitter = new SourceEmitter();

    /**
     * 构造反编译引擎实例.
     *
     * @param config      反编译配置
     * @param diagnostics 诊断信息监听器
     */
    public BdecEngine(BdecConfig config, DiagnosticListener diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
    }

    @Override
    public String getName() {return "bdec";}

    @Override
    public String getVersion() {return "0.1.0";}

    /**
     * 执行完整的反编译流程.
     *
     * <p>反编译流程分为以下阶段:</p>
     * <ol>
     *   <li>解析 class 文件</li>
     *   <li>逐方法构建控制流图(CFG)</li>
     *   <li>生成线性中间表示(LinearIr),并进行语义重建</li>
     *   <li>SSA(静态单赋值)构建与优化(复制传播,死代码消除)</li>
     *   <li>结构化控制流</li>
     *   <li>构建抽象语法树(AST)</li>
     *   <li>AST 重写(应用各类语义恢复规则)</li>
     *   <li>生成 Java 源代码</li>
     * </ol>
     *
     * @param internalName 类的内部名称(如 {@code com/example/Foo})
     * @param classBytes   class 文件的字节数组
     * @param context      反编译上下文
     * @return 反编译结果,包含生成的源代码和诊断信息
     */
    @Override
    public BdecResult decompile(String internalName, byte[] classBytes, DecompileContext context) {
        List<String> warnings = new ArrayList<>();

        try {
            // ===== 阶段 1:解析 class 文件 =====
            ClassFileModel classFile = classReader.read(internalName, classBytes);
            diagnostics.report(DecompilerDiagnostic.info("parser", internalName,
                    "parsed v" + classFile.majorVersion() + ", "
                            + classFile.methods().size() + " methods, "
                            + classFile.fields().size() + " fields"));

            // 用已解析的 class 文件模型和 BootstrapMethod 数据丰富上下文.
            // LambdaRewriter,MethodRefRewriter,EnumRewriter 等重写规则
            // 需要这些字节码级别的数据进行分析.
            context = new DecompileContext(context.config(), context::loadClassBytes,
                    classFile.bootstrapMethods(), classFile);

            // ===== 阶段 2-4:逐方法反编译 =====
            List<StructuredMethod> structuredMethods = new ArrayList<>();
            for (MethodModel method : classFile.methods()) {
                if (method.isAbstract() || method.isNative()) {
                    // 保留抽象方法和本地方法声明(无方法体,无需 IR)
                    structuredMethods.add(new StructuredMethod(method, null, null));
                    continue;
                }
                if (method.instructions() == null || method.instructions().isEmpty()) {
                    continue;
                }

                try {
                    // 阶段 2:构建控制流图(CFG)
                    ControlFlowGraph cfg = cfgBuilder.build(method);

                    // 阶段 3:生成线性中间表示(LinearIr)
                    LinearIr ir = irBuilder.build(cfg, method, classFile.constantPool(),
                            classFile.bootstrapMethods());

                    // 阶段 3.5:语义重建
                    ir = semanticReconstructor.reconstruct(ir, method, cfg, classFile);

                    // 阶段 3b:SSA 构建 + 类型推断 + 优化
                    if (config.ssaThreshold() > 0 && ir.instructions().size() >= config.ssaThreshold()) {
                        try {
                            SsaForm ssa = ssaBuilder.build(ir);
                            Map<Integer, com.bingbaihanji.bdec.type.JavaType> inferred =
                                    typeInference.infer(ssa);
                            // 对 SSA 指令执行死代码消除和复制传播优化
                            List<IrInstruction> optimized =
                                    dce.eliminate(copyPropagation.propagate(ssa.instructions()));
                            ir = new LinearIr(method, cfg, optimized, ir.variables());
                            ir.setSsaOptimized(true);
                            diagnostics.report(DecompilerDiagnostic.info("ssa", internalName,
                                    "SSA: " + ssa.instructions().size() + " insns ("
                                            + ssa.varVersionCount().size() + " vars)"));
                        } catch (Exception e) {
                            diagnostics.report(DecompilerDiagnostic.warning("ssa", internalName,
                                    method.name() + method.descriptor(), -1,
                                    "SSA construction failed: " + e.getMessage()));
                        }
                    }

                    // 阶段 4:结构化控制流
                    StructuredMethod sm = structurer.structure(ir, context);
                    structuredMethods.add(sm);
                } catch (Exception e) {
                    diagnostics.report(DecompilerDiagnostic.warning("structuring",
                            internalName, method.name() + method.descriptor(),
                            -1, "failed to decompile method: " + e.getMessage()));
                }
            }

            // 阶段 5:构建抽象语法树(AST)
            CompilationUnit unit = astBuilder.build(classFile, structuredMethods, context);

            // 阶段 5b:应用 AST 重写规则
            unit = astRewriter.rewrite(unit, config, context);

            // 阶段 6:生成 Java 源代码
            SourceFile source = sourceEmitter.emit(unit, config);

            return new BdecResult(true, source.source(), null, warnings,
                    source.sourceLineToBytecodeOffset());

        } catch (Exception e) {
            diagnostics.report(DecompilerDiagnostic.error("emit", internalName,
                    null, -1, "decompilation failed: " + e.getMessage(), e));
            return BdecResult.error(e, warnings);
        }
    }
}
