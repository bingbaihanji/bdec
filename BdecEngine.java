package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ast.AstBuilder;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.rewrite.AstRewriter;
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
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;

import java.util.ArrayList;
import java.util.List;

public class BdecEngine implements Decompiler {

    private final BdecConfig config;

    private final DiagnosticListener diagnostics;

    private final ClassFileReader classReader = new ClassFileReader();

    private final CfgBuilder cfgBuilder = new CfgBuilder();

    private final IrBuilder irBuilder = new IrBuilder();

    private final ControlFlowStructurer structurer = new ControlFlowStructurer();

    private final AstBuilder astBuilder = new AstBuilder();

    private final AstRewriter astRewriter = new AstRewriter(List.of());

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
                    LinearIr ir = irBuilder.build(cfg, method);

                    // Phase 4: Structuring
                    StructuredMethod sm = structurer.structure(ir, context);
                    structuredMethods.add(sm);
                } catch (Exception e) {
                    diagnostics.report(DecompilerDiagnostic.warning("structuring",
                            internalName, method.name() + method.descriptor(),
                            -1, "failed to decompile method: " + e.getMessage()));
                }
            }

            // Phase 5: AST
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
