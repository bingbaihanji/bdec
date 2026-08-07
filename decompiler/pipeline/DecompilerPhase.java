package com.bingbaihanji.bdec.decompiler.pipeline;

import com.bingbaihanji.bdec.decompiler.DecompileContext;
import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnosticListener;

/**
 * A replaceable stage in the decompiler pipeline.
 *
 * @param <I> stage input type
 * @param <O> stage output type
 */
public interface DecompilerPhase<I, O> {

    String name();

    O run(I input, DecompileContext context, DecompilerDiagnosticListener diagnostics);
}
