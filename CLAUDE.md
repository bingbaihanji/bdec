# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BDEC (Bingbaihanji Decompiler Engine Core) — a Java 25 bytecode decompiler: `.class`/`.jar` in, readable and re-compilable Java source out. Single Maven module, no runtime dependencies (JUnit 4 is test-only). Comments/Javadoc are in Chinese; UTF-8; 4-space indent. Public members carry Javadoc — keep that convention.

## Commands

Requires **JDK 25** (round-trip tests invoke `javac` via `javax.tools`; a JRE is not enough) and Maven 3.9+. Run from this directory.

- `mvn package` — build. The shaded executable jar lands in **`bin/bdec-1.0.0-shaded.jar`** (shade plugin `outputDirectory` = `bin/`), main class `com.bingbaihanji.bdec.App` (delegates to `BdecCli`). `tools/diff-test/diff_test.py` prefers this path for `BDEC_JAR` and falls back to `target/bdec.jar` when it is absent; set `BDEC_JAR` explicitly to override.
- CLI: `java -jar bin/bdec-1.0.0-shaded.jar -class "Foo.class" ./out` or `-jar "app.jar" ./out` (output follows package structure); `--help` / `--version`.
- `mvn test` — full suite (~357 tests).
- `mvn test -Dtest=ArraySetRoundTripTest` — one test class; append `#testMethodName` for a single test.
- `mvn verify` — runs checkstyle as a gate (`config/checkstyle/checkstyle.xml`, fails at warning severity). The checkstyle core is pinned to 12.0.1 in `pom.xml` because the plugin-bundled grammar cannot parse Java 25 syntax — don't remove that pin.
- `mvn test -Dtest=CfrDiffTest` — CFR differential test. Needs the CFR jar via `-Dcfr.jar=/path/to/cfr-0.152.jar` or in the Maven local repo (`org/benf/cfr/0.152/`); auto-skips when absent.
- `python tools/diff-test/diff_test.py [--samples DIR] [--keep]` — batch "BDEC vs CFR both must recompile" matrix over sample dirs (needs built jar + CFR).
- When multiple parallel agents run Maven concurrently, serialize through `bash tools/run-with-lock.sh mvn ...` (file lock at `.claude/build.lock`).

## Architecture

`BdecEngine.decompile()` orchestrates a fixed-order pipeline; each stage is an independent, replaceable component. The design follows CFR/Vineflower's **"one handler per pattern"** principle: every high-level semantic (for-each, try-with-resources, switch expression, pattern matching, ternary, string concat, …) is restored by its own dedicated recognizer/rewriter.

```
class bytes
  → ClassFileReader            bytecode/parser: header, constant pool, methods, fields
  → per method (MethodPipeline):
      CfgBuilder               instructions → basic blocks + ControlFlowEdge (cfg/)
      IrBuilder                stack simulation → register-style LinearIr (ir/)
      SemanticReconstructor    pre-structuring patterns: DefUse expression merging,
                               type-aware constant folding, ctor expansion,
                               requireNonNull elimination, synchronized recognition
                               (semantic/)
      [SsaBuilder → CopyPropagation → DeadCodeElimination]
                               optional, gated by ssaThreshold (ir/)
      ControlFlowStructurer    dominator/post-dominator pre-analysis, then iterative
                               folding of loops & sequential blocks (structuring/)
  → AstBuilder                 StructuredMethod + model → CompilationUnit AST (ast/)
  → AstRewriter                ~21 RewriteRules, applied in the fixed order declared in
                               BdecEngine (ast/rewrite/; StringConcatRewriter restores
                               string concatenation)
  → InnerClassDecompiler / AnonymousClassRewriter / InnerClassRewriter
                               inner/anonymous class inlining; needs the DecompileContext
                               byte loader for sibling class bytes
  → SourceEmitter              formatting, imports, statement-level line→bytecode
                               mapping (partial for rebuilt statements; emit/)
```

Big-picture facts that take reading several files to piece together:

- **structuring/ is the core layer.** `ControlFlowStructurer` never mutates the CFG in place — each fold creates a new CFG snapshot. `BlockReducer` + `ExprTranslator`/`ExprCleanup` + `ReducerOps` (callback interface) do the heavy translation; per-shape translators handle if/ternary (`IfTranslator` folds PHI merges back into `cond ? a : b`), loops (`LoopTranslator`), switch (`SwitchTranslator`), try/finally (`TryTranslator`, `FinallyRecognizer`), synchronized, and lambda/indy (`IndyTranslator`). Irreducible CFGs fall back to flat labeled-goto output (`IrreducibleHandler`) — semantically correct but not re-compilable (goto is a keyword).
- **Rewrite chain order is load-bearing.** The order in `BdecEngine`'s `AstRewriter` constructor is deliberate (e.g. `RedundantCastRewriter` must run after `ForEachRewriter`; `SourceCleanup` runs last). When adding a rewriter, place it with respect to these dependencies rather than appending blindly.
- **`DecompileContext`** carries `BdecConfig` plus a `Function<String, byte[]>` loader that resolves inner/sibling class bytes (enum constant bodies, anonymous classes). Round-trip tests use a temp-dir loader (`DecompileTestHarness.decompileWithInnerLoader`); JAR mode uses a map-backed loader built from the archive.
- **Type names are rendered import-aware** via `util/TypeNameRenderer` / `TypeText` — don't hand-roll short-name resolution in emitters.
- **Debug:** `BdecConfig.debug()` (or `debugDumpCfg`/`debugDumpAst` builder flags) dumps CFG DOT graphs and AST trees.
- Generic-signature restoration uses lightweight reflection in `ir/GenericMethodResolver` (for `java.*` methods) plus Signature-attribute parsing (`TypeReferenceUtil`, `AstBuilder`).

## Testing

Three layers, each strictly stronger than the previous (compiles ≠ behaves correctly):

1. **Round-trip** — `DecompileTestHarness` compiles source with `javax.tools.JavaCompiler`, decompiles, asserts, and recompiles:
   - `decompileWithInnerLoader(source, className)` is the standard entry point (resolves inner/anonymous classes).
   - `assertContains` / `assertNotContains` use **exact text matching — regex heuristics are forbidden** in assertions.
   - `assertRecompiles(decompiled, className)` verifies the output is legal Java.
   - New regression tests are named `XxxRoundTripTest` under `src/test/java/com/bingbaihanji/bdec/`.
2. **CFR differential** — `CfrDiffTest` + `tools/diff-test/diff_test.py`: both BDEC's and CFR's outputs must recompile for the same input.
3. **Execution-level semantic equivalence** — `SemanticEquivalenceHarness` (check() round-trip): decompile → recompile → run original and recompiled with the same input, compare exit code + stdout. Behavior samples live in `src/test/resources/behavior-samples/` and follow the convention `public static String check()` + `main`. This layer catches silent "compiles but behaves differently" bugs — prefer it when fixing semantic (not just syntactic) issues.

Fixtures: `src/test/java/com/bingbaihanji/test/collection/` holds real-world sources-as-data (ArrayMap, MapUtils, …) that tests decompile — not normal tests. `src/test/resources/decompile-samples/` feeds `diff_test.py`.

## Repository Conventions

- Commit prefixes: `feat:` / `fix:` / `docs:` / `ci:`; messages in Chinese are fine; describe root cause and verification results in commit/PR text.
- CI (`.github/workflows/main-src.yml`) auto-syncs `src/main/java/com/bingbaihanji/bdec` into the `main-src` branch — never edit `main-src` manually.
- The README's "已知限制 / Known Limitations" section is the authoritative list of deliberate gaps (irreducible CFG goto fallback, block-level exception-edge approximation, JAR external dependency loading, reserved debug/line-mapping fields). Don't "fix" these casually; they are documented trade-offs.
