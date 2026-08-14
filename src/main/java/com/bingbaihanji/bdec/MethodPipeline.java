package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticListener;
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
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 逐方法反编译管线(里程碑 Phase 3).
 *
 * <p>将单个方法从字节码提升为 {@link StructuredMethod}:CFG → LinearIr → 语义重建 →
 * (可选)SSA 优化 → 结构化控制流.供 {@link BdecEngine} 主循环与内部类反编译复用,
 * 消除两份逐方法循环的重复.</p>
 */
public final class MethodPipeline {

    private final BdecConfig config;

    private final DiagnosticListener diagnostics;

    private final CfgBuilder cfgBuilder = new CfgBuilder();

    private final IrBuilder irBuilder = new IrBuilder();

    private final SemanticReconstructor semanticReconstructor = new SemanticReconstructor();

    private final SsaBuilder ssaBuilder = new SsaBuilder();

    private final TypeInference typeInference = new TypeInference();

    private final CopyPropagation copyPropagation = new CopyPropagation();

    private final DeadCodeElimination dce = new DeadCodeElimination();

    private final ControlFlowStructurer structurer = new ControlFlowStructurer();

    /**
     * 构造逐方法反编译管线.
     *
     * @param config      反编译配置
     * @param diagnostics 诊断信息监听器
     */
    public MethodPipeline(BdecConfig config, DiagnosticListener diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
    }

    /**
     * 反编译一个类的全部方法.
     *
     * @param classFile    已解析的 class 文件模型
     * @param context      反编译上下文
     * @param runSsa       是否执行 SSA 优化(主类为 true,内部类为 false)
     * @param reportErrors 方法反编译失败时是否报告诊断(主类为 true,内部类静默跳过)
     * @return 结构化方法列表(抽象/本地方法为占位声明)
     */
    public List<StructuredMethod> decompileMethods(ClassFileModel classFile,
                                                   DecompileContext context,
                                                   boolean runSsa,
                                                   boolean reportErrors) {
        List<StructuredMethod> result = new ArrayList<>();
        for (MethodModel method : classFile.methods()) {
            if (method.isAbstract() || method.isNative()) {
                // 保留抽象方法和本地方法声明(无方法体,无需 IR)
                result.add(new StructuredMethod(method, null, null));
                continue;
            }
            if (method.instructions() == null || method.instructions().isEmpty()) {
                continue;
            }
            try {
                result.add(decompileMethod(method, classFile, context, runSsa));
            } catch (Exception e) {
                if (reportErrors) {
                    diagnostics.report(DecompilerDiagnostic.warning("structuring",
                            classFile.internalName(), method.name() + method.descriptor(),
                            -1, "failed to decompile method: " + e.getMessage()));
                }
            }
        }
        return result;
    }

    /** 反编译单个方法:CFG → LinearIr → 语义重建 → (可选)SSA → 结构化. */
    private StructuredMethod decompileMethod(MethodModel method, ClassFileModel classFile,
                                             DecompileContext context, boolean runSsa) {
        ControlFlowGraph cfg = cfgBuilder.build(method);
        LinearIr ir = irBuilder.build(cfg, method, classFile.constantPool(),
                classFile.bootstrapMethods());
        ir = semanticReconstructor.reconstruct(ir, method, cfg, classFile);
        if (runSsa) {
            ir = applySsa(method, cfg, ir, classFile.internalName());
        }
        return structurer.structure(ir, context);
    }

    /** SSA 构建 + 类型推断 + 优化(死代码消除与复制传播). */
    private LinearIr applySsa(MethodModel method, ControlFlowGraph cfg, LinearIr ir,
                              String internalName) {
        if (config.ssaThreshold() <= 0 || ir.instructions().size() < config.ssaThreshold()) {
            return ir;
        }
        try {
            SsaForm ssa = ssaBuilder.build(ir);
            Map<Integer, JavaType> inferred = typeInference.infer(ssa);
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
        return ir;
    }
}
