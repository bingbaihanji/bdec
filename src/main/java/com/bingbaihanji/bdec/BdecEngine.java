package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ast.AstBuilder;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.rewrite.AstRewriter;
import com.bingbaihanji.bdec.ast.rewrite.BoxingRewriter;
import com.bingbaihanji.bdec.ast.rewrite.EnumRewriter;
import com.bingbaihanji.bdec.ast.rewrite.ForEachRewriter;
import com.bingbaihanji.bdec.ast.rewrite.LambdaRewriter;
import com.bingbaihanji.bdec.ast.rewrite.MethodRefRewriter;
import com.bingbaihanji.bdec.ast.rewrite.PatternMatchRewriter;
import com.bingbaihanji.bdec.ast.rewrite.RecordRewriter;
import com.bingbaihanji.bdec.ast.rewrite.RewriteRule;
import com.bingbaihanji.bdec.ast.rewrite.SealedClassRewriter;
import com.bingbaihanji.bdec.ast.rewrite.StringConcatRewriter;
import com.bingbaihanji.bdec.ast.rewrite.SwitchExprRewriter;
import com.bingbaihanji.bdec.ast.rewrite.TernaryRewriter;
import com.bingbaihanji.bdec.ast.rewrite.TextBlockRewriter;
import com.bingbaihanji.bdec.ast.rewrite.TryResourceRewriter;
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

public class BdecEngine implements Decompiler {

    private final BdecConfig config;

    private final DiagnosticListener diagnostics;

    private final ClassFileReader classReader = new ClassFileReader();

    private final CfgBuilder cfgBuilder = new CfgBuilder();

    private final IrBuilder irBuilder = new IrBuilder();

    private final ControlFlowStructurer structurer = new ControlFlowStructurer();

    private final SsaBuilder ssaBuilder = new SsaBuilder();

    private final TypeInference typeInference = new TypeInference();

    private final CopyPropagation copyPropagation = new CopyPropagation();

    private final DeadCodeElimination dce = new DeadCodeElimination();

    private final SemanticReconstructor semanticReconstructor = new SemanticReconstructor();

    private final AstBuilder astBuilder = new AstBuilder();

    private final AstRewriter astRewriter = new AstRewriter(
            List.of(new RecordRewriter(), new SealedClassRewriter(),
                    new LambdaRewriter(), new MethodRefRewriter(),
                    new StringConcatRewriter(), new TextBlockRewriter(),
                    new ForEachRewriter(), new TryResourceRewriter(),
                    new SwitchExprRewriter(), new PatternMatchRewriter(),
                    new TernaryRewriter(), new BoxingRewriter(),
                    new EnumRewriter()));

    private final SourceEmitter sourceEmitter = new SourceEmitter();

    public BdecEngine(BdecConfig config, DiagnosticListener diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
    }

    @Override
    public String getName() {return "bdec";}

    @Override
    public String getVersion() {return "0.1.0";}

    @Override
    public BdecResult decompile(String internalName, byte[] classBytes, DecompileContext context) {
        List<String> warnings = new ArrayList<>();

        try {
            // Phase 1: Parse class file
            ClassFileModel classFile = classReader.read(internalName, classBytes);
            diagnostics.report(DecompilerDiagnostic.info("parser", internalName,
                    "parsed v" + classFile.majorVersion() + ", "
                            + classFile.methods().size() + " methods, "
                            + classFile.fields().size() + " fields"));

            // Enrich context with parsed bootstrap methods (needed by LambdaRewriter)
            if (!classFile.bootstrapMethods().isEmpty()) {
                context = new DecompileContext(context.config(), context::loadClassBytes,
                        classFile.bootstrapMethods());
            }

            // Phase 2-4: Per-method decompilation
            List<StructuredMethod> structuredMethods = new ArrayList<>();
            for (MethodModel method : classFile.methods()) {
                if (method.isAbstract() || method.isNative()) {
                    continue;
                }
                if (method.instructions() == null || method.instructions().isEmpty()) {
                    continue;
                }

                try {
                    // Phase 2: CFG
                    ControlFlowGraph cfg = cfgBuilder.build(method);

                    // Phase 3: LinearIr
                    LinearIr ir = irBuilder.build(cfg, method, classFile.constantPool());

                    // Phase 3.5: Semantic Reconstruction (NEW)
                    ir = semanticReconstructor.reconstruct(ir, method, cfg, classFile);

                    // Phase 3b: SSA + Type Inference + Optimization
                    if (config.ssaThreshold() > 0 && ir.instructions().size() >= config.ssaThreshold()) {
                        try {
                            SsaForm ssa = ssaBuilder.build(ir);
                            Map<Integer, com.bingbaihanji.bdec.type.JavaType> inferred =
                                    typeInference.infer(ssa);
                            // DCE on SSA instructions
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

                    // Phase 4: Structuring
                    StructuredMethod sm = structurer.structure(ir, context);
                    structuredMethods.add(sm);
                } catch (Exception e) {
                    diagnostics.report(DecompilerDiagnostic.warning("structuring",
                            internalName, method.name() + method.descriptor(),
                            -1, "failed to decompile method: " + e.getMessage()));
                }
            }

            // Phase 5: AST (semantic reconstruction done before structuring)
            CompilationUnit unit = astBuilder.build(classFile, structuredMethods, context);

            // Phase 5b: Rewrite
            unit = astRewriter.rewrite(unit, config, context);

            // Phase 6: Emit
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
