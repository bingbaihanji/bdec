package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.LinearIr;

/**
 * Orchestrator for the semantic reconstruction pipeline.
 *
 * Runs a series of passes over the LinearIr to recover high-level Java
 * semantics from raw bytecode IR. Passes are re-run in a fixpoint loop
 * until no more changes are made (max 10 iterations).
 *
 * This layer is what separates BDEC from being an "IR pretty-printer"
 * and makes it a proper decompiler.
 *
 * Pipeline order (matches CFR Op03Rewriters + Procyon AstOptimizer):
 * <ol>
 *   <li>DefUse expression merging (inline single-use temporaries)</li>
 *   <li>Type-aware constant folding (0/1 → false/true)</li>
 *   <li>Constructor expansion (this()/super() folding)</li>
 *   <li>RequireNonNull elimination</li>
 *   <li>Synchronized block recognition</li>
 * </ol>
 */
public final class SemanticReconstructor {

    private final TypeAwareConstantFolder typeAwareConstantFolder
            = new TypeAwareConstantFolder();

    private final ConstructorExpander constructorExpander
            = new ConstructorExpander();

    private final RequireNonNullEliminator requireNonNullEliminator
            = new RequireNonNullEliminator();

    private final SynchronizedRecognizer synchronizedRecognizer
            = new SynchronizedRecognizer();

    private final DefUseExpressionMerger defUseExpressionMerger
            = new DefUseExpressionMerger();

    /**
     * Run all semantic reconstruction passes on a method's IR.
     *
     * @param ir        the Linear IR to reconstruct
     * @param method    the method model (for type info, constructor detection)
     * @param cfg       the control flow graph (for exception handler analysis)
     * @param classFile the class file model (for superclass name)
     * @return the reconstructed IR (may be the same object if no changes)
     */
    public LinearIr reconstruct(LinearIr ir, MethodModel method,
                                ControlFlowGraph cfg, ClassFileModel classFile) {
        int maxIterations = 10;
        boolean changed = true;

        while (changed && maxIterations-- > 0) {
            changed = false;

            // 1. Def-use expression merging (first — makes other passes more effective)
            changed |= defUseExpressionMerger.merge(ir);

            // 2. Type-aware constant folding
            changed |= typeAwareConstantFolder.fold(ir, method);

            // 3. Constructor expansion
            changed |= constructorExpander.expand(ir, method, classFile);

            // 4. RequireNonNull elimination
            changed |= requireNonNullEliminator.eliminate(ir);

            // 5. Synchronized block recognition
            changed |= synchronizedRecognizer.recognize(ir, cfg);
        }

        return ir;
    }
}
