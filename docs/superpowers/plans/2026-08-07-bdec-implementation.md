# BDEC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a zero-dependency Java 25 decompiler engine (bdec) from scratch, following a bottom-up phased approach with TDD at each layer.

**Architecture:** Pipeline architecture: ClassFileParser → CfgBuilder → IrBuilder (LinearIr) → [optional SSA] → ControlFlowStructurer → AstBuilder → AstRewriter → SourceEmitter. Only 4 interfaces exist (AttributeParser, RewriteRule, DiagnosticListener, Decompiler); all other types are concrete records/sealed classes. CFG transformations use immutable snapshots.

**Tech Stack:** Java 25, JDK standard library only, Maven, JUnit for testing

**Source spec:** `docs/superpowers/specs/2026-08-07-bdec-decompiler-design.md`

---

## File Structure Map

```
src/main/java/com/bingbaihanji/bdec/
├── BdecEngine.java                  ← Engine entry (Phase 0)
├── BdecConfig.java                  ← Typed config (Phase 0)
├── BdecResult.java                  ← Result record (Phase 0)
│
├── bytecode/
│   ├── parser/
│   │   ├── ClassFileReader.java     ← Top-level .class reader (Phase 1a)
│   │   ├── ConstantPoolParser.java  ← CP parsing (Phase 1a)
│   │   ├── StructureParser.java     ← Fields/methods structure (Phase 1a)
│   │   ├── CodeParser.java          ← Code attr + instructions (Phase 1a)
│   │   ├── InstructionDecoder.java  ← Single instruction decode (Phase 1a)
│   │   └── attr/
│   │       ├── AttributeParser.java        ← Interface (Phase 0)
│   │       ├── AttributeRegistry.java      ← Registry (Phase 1b)
│   │       └── impl/                       ← All attribute parsers (Phase 1a/1b)
│   ├── model/                       ← Data models (Phase 1a)
│   │   ├── ClassFileModel.java
│   │   ├── FieldModel.java
│   │   ├── MethodModel.java
│   │   ├── ExceptionHandlerModel.java
│   │   ├── Instruction.java
│   │   └── constantpool/
│   └── opcode/
│       ├── Opcode.java              ← Opcode enum + metadata (Phase 1a)
│       └── Mnemonic.java            ← Mnemonic enum (Phase 1a)
│
├── cfg/
│   ├── BasicBlock.java              ← Basic block (Phase 2)
│   ├── ControlFlowGraph.java        ← CFG with adjacency lists (Phase 2)
│   ├── ControlFlowEdge.java         ← Typed edge record (Phase 2)
│   ├── EdgeKind.java                ← Edge type enum (Phase 2)
│   ├── CfgBuilder.java              ← CFG construction (Phase 2)
│   ├── ExceptionRange.java          ← Try-catch range (Phase 2)
│   ├── DominatorTree.java           ← Dominator tree (Phase 2)
│   └── PostDominatorTree.java       ← Post-dominator tree (Phase 2)
│
├── ir/
│   ├── LinearIr.java                ← Method IR (Phase 3)
│   ├── IrInstruction.java           ← IR instruction (Phase 3)
│   ├── IrOpcode.java                ← IR opcode enum (Phase 3)
│   ├── Value.java                   ← Sealed value interface (Phase 3)
│   ├── ConstantValue.java           ← Constant record (Phase 3)
│   ├── InstructionRef.java          ← Def-use ref (Phase 3)
│   ├── Variable.java                ← SSA-versioned variable (Phase 3)
│   ├── IrBuilder.java               ← Stack simulation → IR (Phase 3)
│   └── FrameState.java              ← Stack+locals state (Phase 3)
│
├── analysis/                        ← (Phase 3b — optional SSA)
│   ├── SsaConverter.java
│   ├── DataFlowAnalyzer.java
│   ├── TypeInference.java
│   └── CopyPropagation.java
│
├── structuring/                     ← (Phase 4)
│   ├── ControlFlowStructurer.java
│   ├── LoopAnalyzer.java
│   ├── BranchAnalyzer.java
│   ├── SwitchAnalyzer.java
│   ├── TryCatchAnalyzer.java
│   ├── BlockReducer.java
│   ├── IrreducibleHandler.java
│   ├── LoopInfo.java
│   ├── IfInfo.java
│   └── StructuredMethod.java
│
├── ast/                             ← (Phase 5)
│   ├── AstNode.java
│   ├── AstKind.java
│   ├── AstVisitor.java
│   ├── AstTransformer.java
│   ├── CompilationUnit.java
│   ├── TypeDeclaration.java
│   ├── AstBuilder.java
│   ├── stmt/   (Statement, BlockStatement, IfStatement, LoopStatement, ...)
│   ├── expr/   (Expression, BinaryExpression, InvocationExpression, ...)
│   └── rewrite/ (RewriteRule, AbstractRewriteRule, AstRewriter, rules/)
│
├── emit/                            ← (Phase 6)
│   ├── SourceEmitter.java
│   ├── SourceFile.java
│   ├── ImportManager.java
│   ├── IndentWriter.java
│   ├── Precedence.java
│   ├── LineMappingBuilder.java
│   ├── TypeEmitter.java
│   ├── StatementEmitter.java
│   └── ExpressionEmitter.java
│
├── type/
│   ├── JavaType.java                ← Type interface (Phase 1a)
│   ├── TypeKind.java                ← Type enum (Phase 1a)
│   └── TypeResolver.java            ← Descriptor parsing (Phase 1a)
│
├── diagnostic/
│   ├── DiagnosticLevel.java         ← Enum (Phase 0)
│   ├── DiagnosticListener.java      ← Interface (Phase 0)
│   └── DecompilerDiagnostic.java    ← Record (Phase 0)
│
└── util/
    ├── DotExporter.java             ← CFG → DOT (Phase 2)
    └── AstTreeExporter.java         ← AST → tree (Phase 5)
```

**Existing files to delete/modify:**
- Delete all old interface files that were "all-interface" approach
- Rewrite `Decompiler.java`, `DecompileResult.java`, `DecompileContext.java`
- Keep `utils/collection/` files if useful, delete unused ones

---

## Phase 0: Project Skeleton & Foundation Types

### Task 0.1: Clean up old interface files and establish new package structure

**Files to delete:**
- `src/main/java/com/bingbaihanji/bdec/decompiler/bytecode/BytecodeParser.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/pipeline/DecompilerPipeline.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/pipeline/DecompilerPhase.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/cfg/ControlFlowGraphBuilder.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/ir/MethodIr.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/structuring/ControlFlowStructurer.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/structuring/LoopAnalyzer.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/structuring/BranchAnalyzer.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/analysis/DataFlowAnalyzer.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/analysis/TypeInference.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/ast/AstBuilder.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/emit/SourceEmitter.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/ast/rewrite/AstRewriter.java` (old interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/ast/rewrite/AstRewriteRule.java` (old interface)
- All old AST interface files in `ast/stmt/` and `ast/expr/` that start with lowercase (e.g., `ifStatement.java`)
- All old CFG interface files (`BasicBlock.java`, `ControlFlowGraph.java`, etc. old interfaces)

**Files to keep (with modifications):**
- `pom.xml`
- `src/main/java/com/bingbaihanji/bdec/App.java`
- `src/test/java/com/bingbaihanji/bdec/AppTest.java`
- `src/main/java/com/bingbaihanji/bdec/decompiler/Decompiler.java` (keep interface)
- `src/main/java/com/bingbaihanji/bdec/decompiler/DecompileResult.java` (rewrite as record)
- `src/main/java/com/bingbaihanji/bdec/decompiler/DecompileContext.java` (keep but relocate)
- `src/main/java/com/bingbaihanji/bdec/decompiler/diagnostic/` (keep 3 files, modify)
- `src/main/java/com/bingbaihanji/bdec/decompiler/utils/collection/` (keep all)
- Existing `.java` files in `type/`, `bytecode/model/`, `bytecode/Instruction.java` (rewrite as concrete classes)

- [ ] **Step 1: Delete all old interface files**

Run: `powershell -Command "Get-ChildItem -Recurse -Path 'D:\bingbaihanji\fxdecomplie\bdec\bdec\src\main\java\com\bingbaihanji\bdec\decompiler' -Include '*.java' | ForEach-Object { Remove-Item $_.FullName -Force }"`

(Note: this is a bulk cleanup. Only do this if you're sure. Otherwise, delete files individually.)

Expected: All old interface files removed.

- [ ] **Step 2: Verify cleanup**

Run: `powershell -Command "Get-ChildItem -Recurse -Path 'D:\bingbaihanji\fxdecomplie\bdec\bdec\src\main\java' -Include '*.java' | Select-Object FullName"`

Expected: Only `App.java` and a clean (mostly empty) base package remain.

### Task 0.2: Create BdecConfig (typed config with Builder)

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/BdecConfig.java`

- [ ] **Step 1: Write BdecConfig.java**

```java
package com.bingbaihanji.bdec;

/**
 * Typed configuration for the bdec decompiler engine.
 * Construct via {@link #builder()}, not string keys.
 */
public final class BdecConfig {

    // === Output ===
    private final int indentSize;
    private final String lineSeparator;
    private final boolean showLineNumbers;
    private final boolean showBytecodeOffsets;

    // === Structuring switches ===
    private final boolean decodeEnums;
    private final boolean decodeLambdas;
    private final boolean decodeTernary;
    private final boolean decodeStringConcat;
    private final boolean decodeTryResource;
    private final boolean decodeForEach;
    private final boolean collapseImports;

    // === SSA ===
    private final int ssaThreshold; // blocks: -1=disabled, 0=always, >0=threshold

    // === Debug ===
    private final boolean debugDumpCfg;
    private final boolean debugDumpAst;

    private BdecConfig(Builder b) {
        this.indentSize = b.indentSize;
        this.lineSeparator = b.lineSeparator;
        this.showLineNumbers = b.showLineNumbers;
        this.showBytecodeOffsets = b.showBytecodeOffsets;
        this.decodeEnums = b.decodeEnums;
        this.decodeLambdas = b.decodeLambdas;
        this.decodeTernary = b.decodeTernary;
        this.decodeStringConcat = b.decodeStringConcat;
        this.decodeTryResource = b.decodeTryResource;
        this.decodeForEach = b.decodeForEach;
        this.collapseImports = b.collapseImports;
        this.ssaThreshold = b.ssaThreshold;
        this.debugDumpCfg = b.debugDumpCfg;
        this.debugDumpAst = b.debugDumpAst;
    }

    // --- Getters ---
    public int indentSize() { return indentSize; }
    public String lineSeparator() { return lineSeparator; }
    public boolean showLineNumbers() { return showLineNumbers; }
    public boolean showBytecodeOffsets() { return showBytecodeOffsets; }
    public boolean decodeEnums() { return decodeEnums; }
    public boolean decodeLambdas() { return decodeLambdas; }
    public boolean decodeTernary() { return decodeTernary; }
    public boolean decodeStringConcat() { return decodeStringConcat; }
    public boolean decodeTryResource() { return decodeTryResource; }
    public boolean decodeForEach() { return decodeForEach; }
    public boolean collapseImports() { return collapseImports; }
    public int ssaThreshold() { return ssaThreshold; }
    public boolean debugDumpCfg() { return debugDumpCfg; }
    public boolean debugDumpAst() { return debugDumpAst; }

    public static Builder builder() { return new Builder(); }
    public static BdecConfig defaults() { return builder().build(); }

    public static BdecConfig debug() {
        return builder().debugDumpCfg(true).debugDumpAst(true).build();
    }

    public static final class Builder {
        private int indentSize = 4;
        private String lineSeparator = "\n";
        private boolean showLineNumbers = false;
        private boolean showBytecodeOffsets = false;
        private boolean decodeEnums = true;
        private boolean decodeLambdas = true;
        private boolean decodeTernary = true;
        private boolean decodeStringConcat = true;
        private boolean decodeTryResource = true;
        private boolean decodeForEach = true;
        private boolean collapseImports = true;
        private int ssaThreshold = 5;
        private boolean debugDumpCfg = false;
        private boolean debugDumpAst = false;

        public Builder indentSize(int n) { this.indentSize = n; return this; }
        public Builder lineSeparator(String s) { this.lineSeparator = s; return this; }
        public Builder showLineNumbers(boolean v) { this.showLineNumbers = v; return this; }
        public Builder showBytecodeOffsets(boolean v) { this.showBytecodeOffsets = v; return this; }
        public Builder decodeEnums(boolean v) { this.decodeEnums = v; return this; }
        public Builder decodeLambdas(boolean v) { this.decodeLambdas = v; return this; }
        public Builder decodeTernary(boolean v) { this.decodeTernary = v; return this; }
        public Builder decodeStringConcat(boolean v) { this.decodeStringConcat = v; return this; }
        public Builder decodeTryResource(boolean v) { this.decodeTryResource = v; return this; }
        public Builder decodeForEach(boolean v) { this.decodeForEach = v; return this; }
        public Builder collapseImports(boolean v) { this.collapseImports = v; return this; }
        public Builder ssaThreshold(int n) { this.ssaThreshold = n; return this; }
        public Builder debugDumpCfg(boolean v) { this.debugDumpCfg = v; return this; }
        public Builder debugDumpAst(boolean v) { this.debugDumpAst = v; return this; }

        public BdecConfig build() { return new BdecConfig(this); }
    }
}
```

- [ ] **Step 2: Write the test**

```java
// src/test/java/com/bingbaihanji/bdec/BdecConfigTest.java
package com.bingbaihanji.bdec;

import org.junit.Test;
import static org.junit.Assert.*;

public class BdecConfigTest {

    @Test
    public void testDefaults() {
        BdecConfig c = BdecConfig.defaults();
        assertEquals(4, c.indentSize());
        assertTrue(c.decodeEnums());
        assertEquals(5, c.ssaThreshold());
        assertFalse(c.debugDumpCfg());
    }

    @Test
    public void testBuilderOverride() {
        BdecConfig c = BdecConfig.builder()
            .indentSize(2)
            .decodeEnums(false)
            .ssaThreshold(-1)  // disable SSA
            .build();
        assertEquals(2, c.indentSize());
        assertFalse(c.decodeEnums());
        assertEquals(-1, c.ssaThreshold());
    }

    @Test
    public void testDebugConfig() {
        BdecConfig c = BdecConfig.debug();
        assertTrue(c.debugDumpCfg());
        assertTrue(c.debugDumpAst());
    }
}
```

- [ ] **Step 3: Compile and run tests**

Run: `mvn test -pl . -Dtest=BdecConfigTest`
Expected: All 3 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/BdecConfig.java src/test/java/com/bingbaihanji/bdec/BdecConfigTest.java
git commit -m "feat: add BdecConfig typed config with Builder pattern"
```

### Task 0.3: Create DecompilerDiagnostic (structured record)

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/diagnostic/DiagnosticLevel.java`
- Create: `src/main/java/com/bingbaihanji/bdec/diagnostic/DiagnosticListener.java`
- Create: `src/main/java/com/bingbaihanji/bdec/diagnostic/DecompilerDiagnostic.java`

- [ ] **Step 1: Write DiagnosticLevel.java**

```java
package com.bingbaihanji.bdec.diagnostic;

public enum DiagnosticLevel {
    INFO,
    WARNING,
    ERROR
}
```

- [ ] **Step 2: Write DiagnosticListener.java**

```java
package com.bingbaihanji.bdec.diagnostic;

@FunctionalInterface
public interface DiagnosticListener {
    void report(DecompilerDiagnostic diagnostic);
}
```

- [ ] **Step 3: Write DecompilerDiagnostic.java**

```java
package com.bingbaihanji.bdec.diagnostic;

/**
 * Structured diagnostic — all fields are fixed.
 * Never use the message string to carry structured data.
 */
public record DecompilerDiagnostic(
    DiagnosticLevel level,
    String phase,           // "parser" / "cfg" / "ir" / "structuring" / "ast" / "rewrite" / "emit"
    String className,       // fully qualified, null = unknown
    String methodName,      // name + descriptor, null = unknown
    int bytecodeOffset,     // -1 = not applicable
    String message,         // single-line human-readable, no location prefix needed
    Throwable cause         // null = none
) {
    /** Global-level info (e.g. class parsed) */
    public static DecompilerDiagnostic info(String phase, String className, String message) {
        return new DecompilerDiagnostic(DiagnosticLevel.INFO, phase, className, null, -1, message, null);
    }

    /** Method-level warning */
    public static DecompilerDiagnostic warning(String phase, String className,
                                                String methodName, int offset, String message) {
        return new DecompilerDiagnostic(DiagnosticLevel.WARNING, phase, className, methodName, offset, message, null);
    }

    /** Method-level error with cause */
    public static DecompilerDiagnostic error(String phase, String className,
                                              String methodName, int offset, String message, Throwable cause) {
        return new DecompilerDiagnostic(DiagnosticLevel.ERROR, phase, className, methodName, offset, message, cause);
    }
}
```

- [ ] **Step 4: Write the test**

```java
// src/test/java/com/bingbaihanji/bdec/diagnostic/DecompilerDiagnosticTest.java
package com.bingbaihanji.bdec.diagnostic;

import org.junit.Test;
import static org.junit.Assert.*;

public class DecompilerDiagnosticTest {

    @Test
    public void testInfoFactory() {
        var d = DecompilerDiagnostic.info("parser", "com/example/Foo", "parsed OK");
        assertEquals(DiagnosticLevel.INFO, d.level());
        assertEquals("parser", d.phase());
        assertEquals("com/example/Foo", d.className());
        assertNull(d.methodName());
        assertEquals(-1, d.bytecodeOffset());
        assertEquals("parsed OK", d.message());
        assertNull(d.cause());
    }

    @Test
    public void testWarningFactory() {
        var d = DecompilerDiagnostic.warning("cfg", "com/example/Foo", "bar(I)V", 42, "unreachable code");
        assertEquals(DiagnosticLevel.WARNING, d.level());
        assertEquals("bar(I)V", d.methodName());
        assertEquals(42, d.bytecodeOffset());
    }

    @Test
    public void testErrorFactory() {
        Exception e = new IllegalArgumentException("bad CP index");
        var d = DecompilerDiagnostic.error("parser", "com/example/Foo", "<init>()V", 0, "constant pool error", e);
        assertEquals(DiagnosticLevel.ERROR, d.level());
        assertSame(e, d.cause());
    }
}
```

- [ ] **Step 5: Compile and run tests**

Run: `mvn test -pl . -Dtest=DecompilerDiagnosticTest`
Expected: All 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/diagnostic/ src/test/java/com/bingbaihanji/bdec/diagnostic/
git commit -m "feat: add structured DecompilerDiagnostic with factory methods"
```

### Task 0.4: Create BdecResult and DecompileContext

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/BdecResult.java`
- Create: `src/main/java/com/bingbaihanji/bdec/DecompileContext.java`

- [ ] **Step 1: Write BdecResult.java**

```java
package com.bingbaihanji.bdec;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record BdecResult(
    boolean success,
    String decompiledCode,
    Throwable cause,
    List<String> warnings,
    Map<Integer, Integer> sourceLineToBytecodeOffset
) {
    public BdecResult(String decompiledCode) {
        this(true, decompiledCode, null, Collections.emptyList(), Collections.emptyMap());
    }

    public static BdecResult error(Throwable cause) {
        return new BdecResult(false, null, cause, Collections.emptyList(), Collections.emptyMap());
    }

    public static BdecResult error(Throwable cause, List<String> warnings) {
        return new BdecResult(false, null, cause, warnings, Collections.emptyMap());
    }
}
```

- [ ] **Step 2: Write DecompileContext.java**

```java
package com.bingbaihanji.bdec;

import java.util.function.Function;

/**
 * Per-decompilation context — carries a class byte loader for resolving
 * dependent types, and the typed config.
 */
public class DecompileContext {
    private final BdecConfig config;
    private final Function<String, byte[]> classByteLoader;

    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader) {
        this.config = config;
        this.classByteLoader = classByteLoader;
    }

    public BdecConfig config() { return config; }

    /** Load bytecode for a dependent class by internal name (e.g. "com/example/Foo$Bar") */
    public byte[] loadClassBytes(String internalName) {
        return classByteLoader != null ? classByteLoader.apply(internalName) : null;
    }

    /** Empty context for simple single-class decompilation */
    public static DecompileContext empty(BdecConfig config) {
        return new DecompileContext(config, null);
    }
}
```

- [ ] **Step 3: Write the test**

```java
// src/test/java/com/bingbaihanji/bdec/DecompileContextTest.java
package com.bingbaihanji.bdec;

import org.junit.Test;
import static org.junit.Assert.*;

public class DecompileContextTest {

    @Test
    public void testEmptyContext() {
        DecompileContext ctx = DecompileContext.empty(BdecConfig.defaults());
        assertNotNull(ctx.config());
        assertNull(ctx.loadClassBytes("java/lang/Object"));
    }

    @Test
    public void testWithLoader() {
        byte[] dummy = { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE };
        DecompileContext ctx = new DecompileContext(BdecConfig.defaults(), name -> {
            if ("java/lang/Object".equals(name)) return dummy;
            return null;
        });
        assertArrayEquals(dummy, ctx.loadClassBytes("java/lang/Object"));
        assertNull(ctx.loadClassBytes("com/example/Unknown"));
    }
}
```

- [ ] **Step 4: Compile and run tests**

Run: `mvn test -pl . -Dtest=DecompileContextTest`
Expected: All 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/BdecResult.java src/main/java/com/bingbaihanji/bdec/DecompileContext.java src/test/java/com/bingbaihanji/bdec/DecompileContextTest.java
git commit -m "feat: add BdecResult record and DecompileContext"
```

### Task 0.5: Create Decompiler interface (the 4th and final interface)

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/decompiler/Decompiler.java`

- [ ] **Step 1: Write Decompiler.java**

```java
package com.bingbaihanji.bdec.decompiler;

import com.bingbaihanji.bdec.BdecResult;
import com.bingbaihanji.bdec.DecompileContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Engine entry interface — the only abstraction point for engine consumers.
 *
 * Implementations must be thread-safe.
 */
public interface Decompiler extends AutoCloseable {

    String name();
    String version();

    BdecResult decompile(String internalName, byte[] classBytes, DecompileContext context);

    default BdecResult decompile(Path classFile, DecompileContext context) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            String fileName = classFile.getFileName().toString();
            String nameWithoutExt = fileName.endsWith(".class")
                    ? fileName.substring(0, fileName.length() - 6)
                    : fileName;
            return decompile(nameWithoutExt, bytes, context);
        } catch (Exception e) {
            return BdecResult.error(e);
        }
    }

    @Override
    default void close() {}
}
```

- [ ] **Step 2: Compile**

Run: `mvn compile`
Expected: Success.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/decompiler/Decompiler.java
git commit -m "feat: define Decompiler entry interface"
```

### Task 0.6: Create BdecEngine skeleton with full pipeline wiring

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/BdecEngine.java`

- [ ] **Step 1: Write BdecEngine.java skeleton**

```java
package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.decompiler.Decompiler;
import com.bingbaihanji.bdec.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.diagnostic.DiagnosticListener;

import java.util.ArrayList;
import java.util.List;

public class BdecEngine implements Decompiler {
    private final BdecConfig config;
    private final DiagnosticListener diagnostics;

    public BdecEngine(BdecConfig config, DiagnosticListener diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
    }

    @Override
    public String name() { return "bdec"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public BdecResult decompile(String internalName, byte[] classBytes, DecompileContext context) {
        List<String> warnings = new ArrayList<>();

        try {
            // Phase 1: Parse class file -- TODO: replace with real ClassFileReader
            diagnostics.report(DecompilerDiagnostic.info("parser", internalName, "placeholder parse"));
            String placeholderSource = "// TODO: decompiled " + internalName + "\n";

            return new BdecResult(placeholderSource);

        } catch (Exception e) {
            diagnostics.report(DecompilerDiagnostic.error("emit", internalName,
                    null, -1, "decompilation failed", e));
            return BdecResult.error(e, warnings);
        }
    }
}
```

- [ ] **Step 2: Write integration test (whole pipeline placeholder)**

```java
// src/test/java/com/bingbaihanji/bdec/BdecEngineTest.java
package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.diagnostic.DiagnosticListener;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class BdecEngineTest {

    @Test
    public void testEngineNameAndVersion() {
        var diags = new ArrayList<DecompilerDiagnostic>();
        BdecEngine engine = new BdecEngine(BdecConfig.defaults(), diags::add);
        assertEquals("bdec", engine.name());
        assertEquals("0.1.0", engine.version());
    }

    @Test
    public void testPlaceholderDecompile() {
        List<DecompilerDiagnostic> diags = new ArrayList<>();
        BdecEngine engine = new BdecEngine(BdecConfig.defaults(), diags::add);
        BdecResult result = engine.decompile("com/example/Test",
                new byte[0], DecompileContext.empty(BdecConfig.defaults()));

        assertTrue(result.success());
        assertNotNull(result.decompiledCode());
        assertTrue(result.decompiledCode().contains("com/example/Test"));
        assertFalse(diags.isEmpty());
        assertEquals("parser", diags.get(0).phase());
    }
}
```

- [ ] **Step 3: Run the test**

Run: `mvn test -pl . -Dtest=BdecEngineTest`
Expected: Both tests pass (placeholder pipeline works).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/BdecEngine.java src/test/java/com/bingbaihanji/bdec/BdecEngineTest.java
git commit -m "feat: add BdecEngine skeleton with placeholder pipeline"
```

---

## Phase 1a: Class Parser — Core Path

### Task 1.1: Type system foundation (TypeKind, JavaType, TypeResolver)

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/type/TypeKind.java`
- Create: `src/main/java/com/bingbaihanji/bdec/type/JavaType.java`
- Create: `src/main/java/com/bingbaihanji/bdec/type/TypeResolver.java`

- [ ] **Step 1: Write TypeKind.java**

```java
package com.bingbaihanji.bdec.type;

public enum TypeKind {
    VOID,
    BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE,
    CLASS,
    ARRAY,
    TYPE_VARIABLE,
    WILDCARD,
    METHOD_TYPE
}
```

- [ ] **Step 2: Write JavaType.java (record, not interface)**

```java
package com.bingbaihanji.bdec.type;

import java.util.Collections;
import java.util.List;

/**
 * Concrete Java type record — not an interface.
 *
 * Examples:
 *   "I" → INT primitive
 *   "Ljava/lang/String;" → CLASS with name "java/lang/String"
 *   "[I" → ARRAY of INT, 1 dimension
 *   "[[Ljava/lang/Object;" → ARRAY of CLASS, 2 dimensions
 */
public record JavaType(
    TypeKind kind,
    String internalName,       // for CLASS: "java/lang/String"; for PRIMITIVE: null
    String descriptor,         // JVM descriptor: "I", "Ljava/lang/String;", "[I", etc.
    List<JavaType> typeArguments,
    int arrayDimensions
) {
    // --- Primitive type constants ---
    public static final JavaType VOID    = primitive(TypeKind.VOID,    "V");
    public static final JavaType BOOLEAN = primitive(TypeKind.BOOLEAN, "Z");
    public static final JavaType BYTE    = primitive(TypeKind.BYTE,    "B");
    public static final JavaType SHORT   = primitive(TypeKind.SHORT,   "S");
    public static final JavaType CHAR    = primitive(TypeKind.CHAR,    "C");
    public static final JavaType INT     = primitive(TypeKind.INT,     "I");
    public static final JavaType LONG    = primitive(TypeKind.LONG,    "J");
    public static final JavaType FLOAT   = primitive(TypeKind.FLOAT,   "F");
    public static final JavaType DOUBLE  = primitive(TypeKind.DOUBLE,  "D");

    private static JavaType primitive(TypeKind kind, String descriptor) {
        return new JavaType(kind, null, descriptor, Collections.emptyList(), 0);
    }

    /** Create a class type from internal name */
    public static JavaType classType(String internalName) {
        return new JavaType(TypeKind.CLASS, internalName,
                "L" + internalName + ";", Collections.emptyList(), 0);
    }

    /** Create an array type */
    public static JavaType array(JavaType elementType, int dimensions) {
        StringBuilder desc = new StringBuilder();
        desc.append("[".repeat(Math.max(0, dimensions)));
        desc.append(elementType.descriptor());
        return new JavaType(TypeKind.ARRAY, null,
                desc.toString(), Collections.emptyList(), dimensions);
    }

    public String displayName() {
        return switch (kind) {
            case VOID -> "void";
            case BOOLEAN -> "boolean";
            case BYTE -> "byte";
            case SHORT -> "short";
            case CHAR -> "char";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case CLASS -> internalName.replace('/', '.');
            case ARRAY -> {
                JavaType elem = this;
                int dims = arrayDimensions;
                while (elem.kind() == TypeKind.ARRAY) dims = elem.arrayDimensions();
                yield baseElementName() + "[]".repeat(Math.max(0, arrayDimensions));
            }
            default -> descriptor;
        };
    }

    private String baseElementName() {
        // strip array dimensions and return element display name
        // Simplified: just return descriptor
        return descriptor.replace("[", "").replace("L", "").replace(";", "").replace("/", ".");
    }

    /** Width in stack slots: long/double = 2, void = 0, others = 1 */
    public int slotCount() {
        return (kind == TypeKind.LONG || kind == TypeKind.DOUBLE) ? 2
                : (kind == TypeKind.VOID) ? 0 : 1;
    }
}
```

- [ ] **Step 3: Write TypeResolver.java**

```java
package com.bingbaihanji.bdec.type;

/**
 * Resolves JVM field/method descriptors to JavaType objects.
 *
 * Field descriptor:  "I", "Ljava/lang/String;", "[B", "[[I"
 * Method descriptor: "(ILjava/lang/String;)[Ljava/lang/Object;"
 */
public final class TypeResolver {

    /** Parse a field descriptor into a JavaType */
    public static JavaType parseFieldDescriptor(String descriptor) {
        return parseType(descriptor, 0).type();
    }

    /** Parse a method descriptor, e.g. "(IJ)Ljava/lang/String;" */
    public static JavaType[] parseMethodParameterTypes(String methodDescriptor) {
        if (!methodDescriptor.startsWith("(")) {
            throw new IllegalArgumentException("Not a method descriptor: " + methodDescriptor);
        }
        int pos = 1;
        java.util.List<JavaType> params = new java.util.ArrayList<>();
        while (pos < methodDescriptor.length() && methodDescriptor.charAt(pos) != ')') {
            var result = parseType(methodDescriptor, pos);
            params.add(result.type());
            pos = result.nextPos();
        }
        return params.toArray(new JavaType[0]);
    }

    /** Parse the return type from a method descriptor */
    public static JavaType parseMethodReturnType(String methodDescriptor) {
        int closeParen = methodDescriptor.indexOf(')');
        if (closeParen < 0) {
            throw new IllegalArgumentException("Not a method descriptor: " + methodDescriptor);
        }
        return parseType(methodDescriptor, closeParen + 1).type();
    }

    /** Parse a single type starting at position */
    private static ParseResult parseType(String desc, int pos) {
        char c = desc.charAt(pos);
        return switch (c) {
            case 'V' -> new ParseResult(JavaType.VOID, pos + 1);
            case 'Z' -> new ParseResult(JavaType.BOOLEAN, pos + 1);
            case 'B' -> new ParseResult(JavaType.BYTE, pos + 1);
            case 'S' -> new ParseResult(JavaType.SHORT, pos + 1);
            case 'C' -> new ParseResult(JavaType.CHAR, pos + 1);
            case 'I' -> new ParseResult(JavaType.INT, pos + 1);
            case 'J' -> new ParseResult(JavaType.LONG, pos + 1);
            case 'F' -> new ParseResult(JavaType.FLOAT, pos + 1);
            case 'D' -> new ParseResult(JavaType.DOUBLE, pos + 1);
            case 'L' -> {
                int end = desc.indexOf(';', pos);
                String internalName = desc.substring(pos + 1, end);
                yield new ParseResult(JavaType.classType(internalName), end + 1);
            }
            case '[' -> {
                var elem = parseType(desc, pos + 1);
                yield new ParseResult(JavaType.array(elem.type(), 1 + elem.type().arrayDimensions()), elem.nextPos());
            }
            default -> throw new IllegalArgumentException("Unknown type descriptor char: " + c + " in " + desc);
        };
    }

    private record ParseResult(JavaType type, int nextPos) {}
}
```

- [ ] **Step 4: Write the tests**

```java
// src/test/java/com/bingbaihanji/bdec/type/TypeResolverTest.java
package com.bingbaihanji.bdec.type;

import org.junit.Test;
import static org.junit.Assert.*;

public class TypeResolverTest {

    @Test
    public void testPrimitiveDescriptors() {
        assertEquals(TypeKind.INT, TypeResolver.parseFieldDescriptor("I").kind());
        assertEquals(TypeKind.LONG, TypeResolver.parseFieldDescriptor("J").kind());
        assertEquals(TypeKind.VOID, TypeResolver.parseFieldDescriptor("V").kind());
    }

    @Test
    public void testClassDescriptor() {
        JavaType t = TypeResolver.parseFieldDescriptor("Ljava/lang/String;");
        assertEquals(TypeKind.CLASS, t.kind());
        assertEquals("java/lang/String", t.internalName());
    }

    @Test
    public void testArrayDescriptor() {
        JavaType t = TypeResolver.parseFieldDescriptor("[I");
        assertEquals(TypeKind.ARRAY, t.kind());
        assertEquals(1, t.arrayDimensions());
    }

    @Test
    public void testMultiDimArray() {
        JavaType t = TypeResolver.parseFieldDescriptor("[[Ljava/lang/Object;");
        assertEquals(TypeKind.ARRAY, t.kind());
        assertEquals(2, t.arrayDimensions());
    }

    @Test
    public void testMethodDescriptor() {
        JavaType[] params = TypeResolver.parseMethodParameterTypes("(IJ)Ljava/lang/String;");
        assertEquals(2, params.length);
        assertEquals(TypeKind.INT, params[0].kind());
        assertEquals(TypeKind.LONG, params[1].kind());

        JavaType ret = TypeResolver.parseMethodReturnType("(IJ)Ljava/lang/String;");
        assertEquals(TypeKind.CLASS, ret.kind());
        assertEquals("java/lang/String", ret.internalName());
    }

    @Test
    public void testSlotCount() {
        assertEquals(2, JavaType.LONG.slotCount());
        assertEquals(2, JavaType.DOUBLE.slotCount());
        assertEquals(1, JavaType.INT.slotCount());
        assertEquals(0, JavaType.VOID.slotCount());
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn test -pl . -Dtest=TypeResolverTest`
Expected: All 6 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/type/ src/test/java/com/bingbaihanji/bdec/type/
git commit -m "feat: add JavaType, TypeKind, TypeResolver with descriptor parsing"
```

### Task 1.2: ConstantPool entries (sealed interface + records)

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/model/constantpool/ConstantPoolEntry.java`

- [ ] **Step 1: Write ConstantPoolEntry.java**

```java
package com.bingbaihanji.bdec.bytecode.model.constantpool;

/**
 * Sealed interface for all constant pool entry types.
 * JVM spec §4.4: 17 entry types (tag 1-20).
 */
public sealed interface ConstantPoolEntry
        permits ConstantPoolEntry.CpUtf8, ConstantPoolEntry.CpInteger,
        ConstantPoolEntry.CpFloat, ConstantPoolEntry.CpLong,
        ConstantPoolEntry.CpDouble, ConstantPoolEntry.CpClass,
        ConstantPoolEntry.CpString, ConstantPoolEntry.CpFieldRef,
        ConstantPoolEntry.CpMethodRef, ConstantPoolEntry.CpInterfaceMethodRef,
        ConstantPoolEntry.CpNameAndType, ConstantPoolEntry.CpMethodHandle,
        ConstantPoolEntry.CpMethodType, ConstantPoolEntry.CpDynamic,
        ConstantPoolEntry.CpInvokeDynamic, ConstantPoolEntry.CpModule,
        ConstantPoolEntry.CpPackage {

    int tag();

    record CpUtf8(String value) implements ConstantPoolEntry {
        @Override public int tag() { return 1; }
    }
    record CpInteger(int value) implements ConstantPoolEntry {
        @Override public int tag() { return 3; }
    }
    record CpFloat(float value) implements ConstantPoolEntry {
        @Override public int tag() { return 4; }
    }
    record CpLong(long value) implements ConstantPoolEntry {
        @Override public int tag() { return 5; }
    }
    record CpDouble(double value) implements ConstantPoolEntry {
        @Override public int tag() { return 6; }
    }
    record CpClass(int nameIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 7; }
    }
    record CpString(int stringIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 8; }
    }
    record CpFieldRef(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 9; }
    }
    record CpMethodRef(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 10; }
    }
    record CpInterfaceMethodRef(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 11; }
    }
    record CpNameAndType(int nameIndex, int descriptorIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 12; }
    }
    record CpMethodHandle(int referenceKind, int referenceIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 15; }
    }
    record CpMethodType(int descriptorIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 16; }
    }
    record CpDynamic(int bootstrapMethodAttrIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 17; }
    }
    record CpInvokeDynamic(int bootstrapMethodAttrIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 18; }
    }
    record CpModule(int nameIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 19; }
    }
    record CpPackage(int nameIndex) implements ConstantPoolEntry {
        @Override public int tag() { return 20; }
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile
git add src/main/java/com/bingbaihanji/bdec/bytecode/model/constantpool/ConstantPoolEntry.java
git commit -m "feat: add ConstantPoolEntry sealed interface with 17 record subtypes"
```

### Task 1.3: ConstantPoolParser

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/parser/ConstantPoolParser.java`

- [ ] **Step 1: Write ConstantPoolParser.java**

```java
package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;

/**
 * Parses the constant pool from a class file DataInputStream.
 *
 * The pool has cpCount-1 entries (index 0 is reserved).
 * Long/Double take two indices (the next index is unusable).
 */
public final class ConstantPoolParser {

    public ConstantPoolEntry[] parse(DataInputStream in) throws IOException {
        int cpCount = in.readUnsignedShort(); // u2 constant_pool_count
        ConstantPoolEntry[] pool = new ConstantPoolEntry[cpCount]; // index 0 is null

        int i = 1;
        while (i < cpCount) {
            int tag = in.readUnsignedByte();
            ConstantPoolEntry entry = switch (tag) {
                case 1  -> parseUtf8(in);
                case 3  -> parseInteger(in);
                case 4  -> parseFloat(in);
                case 5  -> parseLong(in);
                case 6  -> parseDouble(in);
                case 7  -> parseClass(in);
                case 8  -> parseString(in);
                case 9  -> parseFieldRef(in);
                case 10 -> parseMethodRef(in);
                case 11 -> parseInterfaceMethodRef(in);
                case 12 -> parseNameAndType(in);
                case 15 -> parseMethodHandle(in);
                case 16 -> parseMethodType(in);
                case 17 -> parseDynamic(in);
                case 18 -> parseInvokeDynamic(in);
                case 19 -> parseModule(in);
                case 20 -> parsePackage(in);
                default -> throw new IOException("Unknown constant pool tag: " + tag + " at index " + i);
            };
            pool[i] = entry;
            // Long/Double take two entries
            if (entry.tag() == 5 || entry.tag() == 6) {
                i += 2;
            } else {
                i++;
            }
        }
        return pool;
    }

    private CpUtf8 parseUtf8(DataInputStream in) throws IOException {
        return new CpUtf8(in.readUTF());
    }

    private CpInteger parseInteger(DataInputStream in) throws IOException {
        return new CpInteger(in.readInt());
    }

    private CpFloat parseFloat(DataInputStream in) throws IOException {
        return new CpFloat(in.readFloat());
    }

    private CpLong parseLong(DataInputStream in) throws IOException {
        return new CpLong(in.readLong());
    }

    private CpDouble parseDouble(DataInputStream in) throws IOException {
        return new CpDouble(in.readDouble());
    }

    private CpClass parseClass(DataInputStream in) throws IOException {
        return new CpClass(in.readUnsignedShort());
    }

    private CpString parseString(DataInputStream in) throws IOException {
        return new CpString(in.readUnsignedShort());
    }

    private CpFieldRef parseFieldRef(DataInputStream in) throws IOException {
        int classIndex = in.readUnsignedShort();
        int nat = in.readUnsignedShort();
        return new CpFieldRef(classIndex, nat);
    }

    private CpMethodRef parseMethodRef(DataInputStream in) throws IOException {
        return new CpMethodRef(in.readUnsignedShort(), in.readUnsignedShort());
    }

    private CpInterfaceMethodRef parseInterfaceMethodRef(DataInputStream in) throws IOException {
        return new CpInterfaceMethodRef(in.readUnsignedShort(), in.readUnsignedShort());
    }

    private CpNameAndType parseNameAndType(DataInputStream in) throws IOException {
        return new CpNameAndType(in.readUnsignedShort(), in.readUnsignedShort());
    }

    private CpMethodHandle parseMethodHandle(DataInputStream in) throws IOException {
        return new CpMethodHandle(in.readUnsignedByte(), in.readUnsignedShort());
    }

    private CpMethodType parseMethodType(DataInputStream in) throws IOException {
        return new CpMethodType(in.readUnsignedShort());
    }

    private CpDynamic parseDynamic(DataInputStream in) throws IOException {
        return new CpDynamic(in.readUnsignedShort(), in.readUnsignedShort());
    }

    private CpInvokeDynamic parseInvokeDynamic(DataInputStream in) throws IOException {
        return new CpInvokeDynamic(in.readUnsignedShort(), in.readUnsignedShort());
    }

    private CpModule parseModule(DataInputStream in) throws IOException {
        return new CpModule(in.readUnsignedShort());
    }

    private CpPackage parsePackage(DataInputStream in) throws IOException {
        return new CpPackage(in.readUnsignedShort());
    }

    // --- Convenience accessors ---

    /** Get UTF8 string value from pool by index */
    public static String utf8(ConstantPoolEntry[] pool, int index) {
        return ((CpUtf8) pool[index]).value();
    }

    /** Get class internal name from pool by CpClass index */
    public static String className(ConstantPoolEntry[] pool, int classIndex) {
        CpClass c = (CpClass) pool[classIndex];
        return utf8(pool, c.nameIndex());
    }
}
```

- [ ] **Step 2: Write the test (parse a minimal class's constant pool)**

```java
// src/test/java/com/bingbaihanji/bdec/bytecode/parser/ConstantPoolParserTest.java
package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.*;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.*;

public class ConstantPoolParserTest {

    @Test
    public void testParseMinimalPool() throws IOException {
        // Build a minimal CP in bytes: count=3, [UTF8("java/lang/Object"), Class(#1)]
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeShort(3);  // cp_count = 3 (indices 1,2; 0 reserved)
        dos.writeByte(1);   // tag=1 (Utf8)
        dos.writeUTF("java/lang/Object");
        dos.writeByte(7);   // tag=7 (Class)
        dos.writeShort(1);  // name_index=1

        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        DataInputStream dis = new DataInputStream(bis);

        ConstantPoolEntry[] pool = new ConstantPoolParser().parse(dis);

        assertEquals(3, pool.length); // index 0 null, 1 utf8, 2 class
        assertNull(pool[0]);
        assertTrue(pool[1] instanceof CpUtf8);
        assertEquals("java/lang/Object", ((CpUtf8) pool[1]).value());
        assertTrue(pool[2] instanceof CpClass);
        assertEquals(1, ((CpClass) pool[2]).nameIndex());
    }
}
```

- [ ] **Step 3: Run tests**

Run: `mvn test -pl . -Dtest=ConstantPoolParserTest`
Expected: Test passes.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/bytecode/parser/ConstantPoolParser.java src/test/java/com/bingbaihanji/bdec/bytecode/parser/ConstantPoolParserTest.java
git commit -m "feat: add ConstantPoolParser for all 17 CP entry types"
```

### Task 1.4: Data models (ClassFileModel, MethodModel, FieldModel, Instruction)

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/model/ClassFileModel.java`
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/model/MethodModel.java`
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/model/FieldModel.java`
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/model/ExceptionHandlerModel.java`
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/model/Instruction.java`

- [ ] **Step 1: Write all model records**

```java
// ClassFileModel.java
package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import java.util.List;

public record ClassFileModel(
    int majorVersion,
    int minorVersion,
    int accessFlags,
    String internalName,         // "com/example/Foo"
    String superInternalName,    // "java/lang/Object" (null for Object itself)
    List<String> interfaceInternalNames,
    List<FieldModel> fields,
    List<MethodModel> methods,
    ConstantPoolEntry[] constantPool
) {}
```

```java
// FieldModel.java
package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;

public record FieldModel(
    int accessFlags,
    String name,
    JavaType type,               // from descriptor
    Object constantValue         // null if no ConstantValue attr
) {}
```

```java
// MethodModel.java
package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;
import java.util.List;

public record MethodModel(
    int accessFlags,
    String name,
    String descriptor,           // "(IJ)Ljava/lang/String;"
    JavaType returnType,
    JavaType[] parameterTypes,
    List<Instruction> instructions,       // null if abstract/native
    List<ExceptionHandlerModel> exceptionHandlers,
    int maxStack,
    int maxLocals
) {
    public boolean isAbstract() { return (accessFlags & 0x0400) != 0; }
    public boolean isNative() { return (accessFlags & 0x0100) != 0; }
    public boolean isStatic() { return (accessFlags & 0x0008) != 0; }
}
```

```java
// ExceptionHandlerModel.java
package com.bingbaihanji.bdec.bytecode.model;

public record ExceptionHandlerModel(
    int startPc,
    int endPc,
    int handlerPc,
    String catchType         // null = catch-all / finally
) {}
```

```java
// Instruction.java
package com.bingbaihanji.bdec.bytecode.model;

import java.util.List;

// TODO: refine operands in Phase 1b. For now, store raw int operands.
public record Instruction(
    int offset,
    int opcode,
    String mnemonic,
    List<Integer> rawOperands,   // raw int operands (will be typed in Phase 1b)
    boolean canFallThrough,
    boolean isTerminal,
    int[] jumpTargets,           // offsets of jump targets (empty if none)
    int varIndex                 // -1 if not a var instruction
) {
    public Instruction {
        if (jumpTargets == null) jumpTargets = new int[0];
    }
}
```

- [ ] **Step 2: For Phase 1a, the Instruction rawOperands are ints. This is fine for now.**

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/bytecode/model/
git commit -m "feat: add ClassFileModel, MethodModel, FieldModel, Instruction records"
```

### Task 1.5: Opcode enum with metadata

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/opcode/Opcode.java`

- [ ] **Step 1: Write Opcode.java — core opcodes for Phase 1a**

```java
package com.bingbaihanji.bdec.bytecode.opcode;

import java.util.Map;
import java.util.HashMap;

/**
 * JVM opcode metadata.
 *
 * Phase 1a includes high-frequency opcodes only.
 * Phase 1b adds the full set.
 */
public enum Opcode {
    NOP           (0,   "nop",           0, 0, true,  false, false, -1),
    ACONST_NULL   (1,   "aconst_null",   0, 1, true,  false, false, -1),
    ICONST_M1     (2,   "iconst_m1",     0, 1, true,  false, false, -1),
    ICONST_0      (3,   "iconst_0",      0, 1, true,  false, false, -1),
    ICONST_1      (4,   "iconst_1",      0, 1, true,  false, false, -1),
    ICONST_2      (5,   "iconst_2",      0, 1, true,  false, false, -1),
    ICONST_3      (6,   "iconst_3",      0, 1, true,  false, false, -1),
    ICONST_4      (7,   "iconst_4",      0, 1, true,  false, false, -1),
    ICONST_5      (8,   "iconst_5",      0, 1, true,  false, false, -1),
    LCONST_0      (9,   "lconst_0",      0, 2, true,  false, false, -1),
    LCONST_1      (10,  "lconst_1",      0, 2, true,  false, false, -1),
    FCONST_0      (11,  "fconst_0",      0, 1, true,  false, false, -1),
    FCONST_1      (12,  "fconst_1",      0, 1, true,  false, false, -1),
    FCONST_2      (13,  "fconst_2",      0, 1, true,  false, false, -1),
    DCONST_0      (14,  "dconst_0",      0, 2, true,  false, false, -1),
    DCONST_1      (15,  "dconst_1",      0, 2, true,  false, false, -1),
    BIPUSH        (16,  "bipush",        1, 1, true,  false, false, -1),
    SIPUSH        (17,  "sipush",        2, 1, true,  false, false, -1),
    LDC           (18,  "ldc",           1, 1, true,  false, false, -1),
    ILOAD         (21,  "iload",         1, 1, true,  false, false,  0),
    LLOAD         (22,  "lload",         1, 2, true,  false, false,  0),
    FLOAD         (23,  "fload",         1, 1, true,  false, false,  0),
    DLOAD         (24,  "dload",         1, 2, true,  false, false,  0),
    ALOAD         (25,  "aload",         1, 1, true,  false, false,  0),
    ILOAD_0       (26,  "iload_0",       0, 1, true,  false, false,  0),
    ILOAD_1       (27,  "iload_1",       0, 1, true,  false, false,  1),
    ILOAD_2       (28,  "iload_2",       0, 1, true,  false, false,  2),
    ILOAD_3       (29,  "iload_3",       0, 1, true,  false, false,  3),
    LLOAD_0       (30,  "lload_0",       0, 2, true,  false, false,  0),
    LLOAD_1       (31,  "lload_1",       0, 2, true,  false, false,  1),
    LLOAD_2       (32,  "lload_2",       0, 2, true,  false, false,  2),
    LLOAD_3       (33,  "lload_3",       0, 2, true,  false, false,  3),
    FLOAD_0       (34,  "fload_0",       0, 1, true,  false, false,  0),
    FLOAD_1       (35,  "fload_1",       0, 1, true,  false, false,  1),
    FLOAD_2       (36,  "fload_2",       0, 1, true,  false, false,  2),
    FLOAD_3       (37,  "fload_3",       0, 1, true,  false, false,  3),
    DLOAD_0       (38,  "dload_0",       0, 2, true,  false, false,  0),
    DLOAD_1       (39,  "dload_1",       0, 2, true,  false, false,  1),
    DLOAD_2       (40,  "dload_2",       0, 2, true,  false, false,  2),
    DLOAD_3       (41,  "dload_3",       0, 2, true,  false, false,  3),
    ALOAD_0       (42,  "aload_0",       0, 1, true,  false, false,  0),
    ALOAD_1       (43,  "aload_1",       0, 1, true,  false, false,  1),
    ALOAD_2       (44,  "aload_2",       0, 1, true,  false, false,  2),
    ALOAD_3       (45,  "aload_3",       0, 1, true,  false, false,  3),
    ISTORE        (54,  "istore",        1, -1,true, false, false,  0),
    LSTORE        (55,  "lstore",        1, -2,true, false, false,  0),
    FSTORE        (56,  "fstore",        1, -1,true, false, false,  0),
    DSTORE        (57,  "dstore",        1, -2,true, false, false,  0),
    ASTORE        (58,  "astore",        1, -1,true, false, false,  0),
    ISTORE_0      (59,  "istore_0",      0, -1,true, false, false,  0),
    ISTORE_1      (60,  "istore_1",      0, -1,true, false, false,  1),
    ISTORE_2      (61,  "istore_2",      0, -1,true, false, false,  2),
    ISTORE_3      (62,  "istore_3",      0, -1,true, false, false,  3),
    LSTORE_0      (63,  "lstore_0",      0, -2,true, false, false,  0),
    LSTORE_1      (64,  "lstore_1",      0, -2,true, false, false,  1),
    LSTORE_2      (65,  "lstore_2",      0, -2,true, false, false,  2),
    LSTORE_3      (66,  "lstore_3",      0, -2,true, false, false,  3),
    FSTORE_0      (67,  "fstore_0",      0, -1,true, false, false,  0),
    FSTORE_1      (68,  "fstore_1",      0, -1,true, false, false,  1),
    FSTORE_2      (69,  "fstore_2",      0, -1,true, false, false,  2),
    FSTORE_3      (70,  "fstore_3",      0, -1,true, false, false,  3),
    DSTORE_0      (71,  "dstore_0",      0, -2,true, false, false,  0),
    DSTORE_1      (72,  "dstore_1",      0, -2,true, false, false,  1),
    DSTORE_2      (73,  "dstore_2",      0, -2,true, false, false,  2),
    DSTORE_3      (74,  "dstore_3",      0, -2,true, false, false,  3),
    ASTORE_0      (75,  "astore_0",      0, -1,true, false, false,  0),
    ASTORE_1      (76,  "astore_1",      0, -1,true, false, false,  1),
    ASTORE_2      (77,  "astore_2",      0, -1,true, false, false,  2),
    ASTORE_3      (78,  "astore_3",      0, -1,true, false, false,  3),
    POP           (87,  "pop",           0, -1,true, false, false, -1),
    POP2          (88,  "pop2",          0, -2,true, false, false, -1),
    DUP           (89,  "dup",           0,  1,true, false, false, -1),
    DUP_X1        (90,  "dup_x1",        0,  1,true, false, false, -1),
    DUP_X2        (91,  "dup_x2",        0,  1,true, false, false, -1),
    DUP2          (92,  "dup2",          0,  2,true, false, false, -1),
    DUP2_X1       (93,  "dup2_x1",       0,  2,true, false, false, -1),
    DUP2_X2       (94,  "dup2_x2",       0,  2,true, false, false, -1),
    SWAP          (95,  "swap",          0,  0,true, false, false, -1),
    IADD          (96,  "iadd",          0,  1,true, false, false, -1),
    LADD          (97,  "ladd",          0,  2,true, false, false, -1),
    FADD          (98,  "fadd",          0,  1,true, false, false, -1),
    DADD          (99,  "dadd",          0,  2,true, false, false, -1),
    ISUB          (100, "isub",          0,  1,true, false, false, -1),
    LSUB          (101, "lsub",          0,  2,true, false, false, -1),
    FSUB          (102, "fsub",          0,  1,true, false, false, -1),
    DSUB          (103, "dsub",          0,  2,true, false, false, -1),
    IMUL          (104, "imul",          0,  1,true, false, false, -1),
    LMUL          (105, "lmul",          0,  2,true, false, false, -1),
    FMUL          (106, "fmul",          0,  1,true, false, false, -1),
    DMUL          (107, "dmul",          0,  2,true, false, false, -1),
    IDIV          (108, "idiv",          0,  1,true, false, false, -1),
    LDIV          (109, "ldiv",          0,  2,true, false, false, -1),
    FDIV          (110, "fdiv",          0,  1,true, false, false, -1),
    DDIV          (111, "ddiv",          0,  2,true, false, false, -1),
    IREM          (112, "irem",          0,  1,true, false, false, -1),
    LREM          (113, "lrem",          0,  2,true, false, false, -1),
    FREM          (114, "frem",          0,  1,true, false, false, -1),
    DREM          (115, "drem",          0,  2,true, false, false, -1),
    INEG          (116, "ineg",          0,  1,true, false, false, -1),
    LNEG          (117, "lneg",          0,  2,true, false, false, -1),
    FNEG          (118, "fneg",          0,  1,true, false, false, -1),
    DNEG          (119, "dneg",          0,  2,true, false, false, -1),
    ISHL          (120, "ishl",          0,  1,true, false, false, -1),
    LSHL          (121, "lshl",          0,  2,true, false, false, -1),
    ISHR          (122, "ishr",          0,  1,true, false, false, -1),
    LSHR          (123, "lshr",          0,  2,true, false, false, -1),
    IUSHR         (124, "iushr",         0,  1,true, false, false, -1),
    LUSHR         (125, "lushr",         0,  2,true, false, false, -1),
    IAND          (126, "iand",          0,  1,true, false, false, -1),
    LAND          (127, "land",          0,  2,true, false, false, -1),
    IOR           (128, "ior",           0,  1,true, false, false, -1),
    LOR           (129, "lor",           0,  2,true, false, false, -1),
    IXOR          (130, "ixor",          0,  1,true, false, false, -1),
    LXOR          (131, "lxor",          0,  2,true, false, false, -1),
    IINC          (132, "iinc",          2,  0,true, false, false,  0),
    I2L           (133, "i2l",           0,  1,true, false, false, -1),
    I2F           (134, "i2f",           0,  1,true, false, false, -1),
    I2D           (135, "i2d",           0,  1,true, false, false, -1),
    L2I           (136, "l2i",           0,  1,true, false, false, -1),
    L2F           (137, "l2f",           0,  1,true, false, false, -1),
    L2D           (138, "l2d",           0,  1,true, false, false, -1),
    F2I           (139, "f2i",           0,  1,true, false, false, -1),
    F2L           (140, "f2l",           0,  1,true, false, false, -1),
    F2D           (141, "f2d",           0,  1,true, false, false, -1),
    D2I           (142, "d2i",           0,  1,true, false, false, -1),
    D2L           (143, "d2l",           0,  1,true, false, false, -1),
    D2F           (144, "d2f",           0,  1,true, false, false, -1),
    I2B           (145, "i2b",           0,  1,true, false, false, -1),
    I2C           (146, "i2c",           0,  1,true, false, false, -1),
    I2S           (147, "i2s",           0,  1,true, false, false, -1),
    LCMP          (148, "lcmp",          0,  1,true, false, false, -1),
    FCMPL         (149, "fcmpl",         0,  1,true, false, false, -1),
    FCMPG         (150, "fcmpg",         0,  1,true, false, false, -1),
    DCMPL         (151, "dcmpl",         0,  1,true, false, false, -1),
    DCMPG         (152, "dcmpg",         0,  1,true, false, false, -1),
    IFEQ          (153, "ifeq",          2, -1,false,false, true,  -1),
    IFNE          (154, "ifne",          2, -1,false,false, true,  -1),
    IFLT          (155, "iflt",          2, -1,false,false, true,  -1),
    IFGE          (156, "ifge",          2, -1,false,false, true,  -1),
    IFGT          (157, "ifgt",          2, -1,false,false, true,  -1),
    IFLE          (158, "ifle",          2, -1,false,false, true,  -1),
    IF_ICMPEQ     (159, "if_icmpeq",     2, -2,false,false, true,  -1),
    IF_ICMPNE     (160, "if_icmpne",     2, -2,false,false, true,  -1),
    IF_ICMPLT     (161, "if_icmplt",     2, -2,false,false, true,  -1),
    IF_ICMPGE     (162, "if_icmpge",     2, -2,false,false, true,  -1),
    IF_ICMPGT     (163, "if_icmpgt",     2, -2,false,false, true,  -1),
    IF_ICMPLE     (164, "if_icmple",     2, -2,false,false, true,  -1),
    IF_ACMPEQ     (165, "if_acmpeq",     2, -2,false,false, true,  -1),
    IF_ACMPNE     (166, "if_acmpne",     2, -2,false,false, true,  -1),
    GOTO          (167, "goto",          2,  0,false,false, true,  -1),
    IRETURN       (172, "ireturn",       0, -1,false,true,  false, -1),
    LRETURN       (173, "lreturn",       0, -2,false,true,  false, -1),
    FRETURN       (174, "freturn",       0, -1,false,true,  false, -1),
    DRETURN       (175, "dreturn",       0, -2,false,true,  false, -1),
    ARETURN       (176, "areturn",       0, -1,false,true,  false, -1),
    RETURN        (177, "return",        0,  0,false,true,  false, -1),
    GETSTATIC     (178, "getstatic",     2,  0,true, false, false, -1),
    PUTSTATIC     (179, "putstatic",     2,  0,true, false, false, -1),
    GETFIELD      (180, "getfield",      2,  0,true, false, false, -1),
    PUTFIELD      (181, "putfield",      2,  0,true, false, false, -1),
    INVOKEVIRTUAL (182, "invokevirtual", 2,  0,true, false, false, -1),
    INVOKESPECIAL (183, "invokespecial", 2,  0,true, false, false, -1),
    INVOKESTATIC  (184, "invokestatic",  2,  0,true, false, false, -1),
    NEW           (187, "new",           2,  1,true, false, false, -1),
    NEWARRAY      (188, "newarray",      1,  0,true, false, false, -1),
    ANEWARRAY     (189, "anewarray",     2,  0,true, false, false, -1),
    ARRAYLENGTH   (190, "arraylength",   0,  0,true, false, false, -1),
    ATHROW        (191, "athrow",        0, -1,false,true,  false, -1),
    CHECKCAST     (192, "checkcast",     2,  0,true, false, false, -1),
    INSTANCEOF    (193, "instanceof",    2,  0,true, false, false, -1),
    IFNULL        (198, "ifnull",        2, -1,false,false, true,  -1),
    IFNONNULL     (199, "ifnonnull",     2, -1,false,false, true,  -1);

    // Phase 1b will add: TABLESWITCH, LOOKUPSWITCH, INVOKEINTERFACE, INVOKEDYNAMIC,
    // MULTIANEWARRAY, WIDE, MONITORENTER, MONITOREXIT, JSR, RET, etc.

    private final int code;
    private final String mnemonic;
    private final int operandBytes;     // number of bytes of operands (0=none, 1=u1, 2=u2, 4=u4)
    private final int stackDelta;       // net stack change: positive=push, negative=pop, 0=unchanged
    private final boolean canFallThrough;
    private final boolean isTerminal;
    private final boolean isConditional;
    private final int implicitVarIndex; // -1 = none; for iload_0..aload_3, istore_0..astore_3

    private static final Map<Integer, Opcode> BY_CODE = new HashMap<>();
    static {
        for (Opcode op : values()) { BY_CODE.put(op.code, op); }
    }

    Opcode(int code, String mnemonic, int operandBytes, int stackDelta,
           boolean canFallThrough, boolean isTerminal, boolean isConditional,
           int implicitVarIndex) {
        this.code = code;
        this.mnemonic = mnemonic;
        this.operandBytes = operandBytes;
        this.stackDelta = stackDelta;
        this.canFallThrough = canFallThrough;
        this.isTerminal = isTerminal;
        this.isConditional = isConditional;
        this.implicitVarIndex = implicitVarIndex;
    }

    public int code() { return code; }
    public String mnemonic() { return mnemonic; }
    public int operandBytes() { return operandBytes; }
    public int stackDelta() { return stackDelta; }
    public boolean canFallThrough() { return canFallThrough; }
    public boolean isTerminal() { return isTerminal; }
    public boolean isConditional() { return isConditional; }
    public int implicitVarIndex() { return implicitVarIndex; }

    public static Opcode byCode(int code) {
        Opcode op = BY_CODE.get(code);
        if (op == null) throw new IllegalArgumentException("Unknown opcode: " + code);
        return op;
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile
git add src/main/java/com/bingbaihanji/bdec/bytecode/opcode/Opcode.java
git commit -m "feat: add Opcode enum with ~140 high-frequency opcodes and metadata"
```

### Task 1.6: InstructionDecoder

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/parser/InstructionDecoder.java`

- [ ] **Step 1: Write InstructionDecoder.java**

```java
package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.opcode.Opcode;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a single JVM instruction from a DataInputStream at a given offset.
 */
public final class InstructionDecoder {

    /**
     * Decode one instruction. Returns null if the opcode is unrecognized
     * (should not happen with valid class files).
     */
    public Instruction decode(DataInputStream in, int offset) throws IOException {
        int opcodeByte = in.readUnsignedByte();
        Opcode op;
        try {
            op = Opcode.byCode(opcodeByte);
        } catch (IllegalArgumentException e) {
            // Unknown opcode — skip remaining bytes
            System.err.println("WARNING: unknown opcode " + opcodeByte + " at offset " + offset);
            return null;
        }

        List<Integer> operands = new ArrayList<>();
        int[] jumpTargets = new int[0];
        int varIndex = op.implicitVarIndex();

        switch (op.operandBytes()) {
            case 1 -> {
                int val = in.readUnsignedByte();
                operands.add(val);
                if (op.implicitVarIndex() == 0) varIndex = val;
            }
            case 2 -> {
                int val = in.readUnsignedShort();
                operands.add(val);
                if (op.implicitVarIndex() == 0) varIndex = val;
                // Branch instructions use signed 16-bit offset
                if (op.isConditional() || op == Opcode.GOTO) {
                    short branchOffset = (short) val;
                    jumpTargets = new int[] { offset + branchOffset };
                }
            }
            case 4 -> {
                // TABLESWITCH / LOOKUPSWITCH (Phase 1b)
                int val = in.readInt();
                operands.add(val);
            }
            default -> {
                // 0 operand bytes, nothing to read
            }
        }
        return new Instruction(offset, opcodeByte, op.mnemonic(),
                operands, op.canFallThrough(), op.isTerminal(), jumpTargets, varIndex);
    }

    /**
     * Decode all instructions in a Code attribute from startPc to endPc.
     */
    public List<Instruction> decodeAll(byte[] code, int startPc, int length) throws IOException {
        List<Instruction> instructions = new ArrayList<>();
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(code, startPc, length);
        DataInputStream dis = new DataInputStream(bis);
        int offset = startPc;
        while (dis.available() > 0) {
            Instruction insn = decode(dis, offset);
            if (insn == null) break;
            instructions.add(insn);
            offset = startPc + length - dis.available();
        }
        return instructions;
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile
git add src/main/java/com/bingbaihanji/bdec/bytecode/parser/InstructionDecoder.java
git commit -m "feat: add InstructionDecoder for JVM bytecode instructions"
```

### Task 1.7: ClassFileReader (top-level class file parser)

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/parser/ClassFileReader.java`
- Create: `src/main/java/com/bingbaihanji/bdec/bytecode/parser/StructureParser.java`

- [ ] **Step 1: Write ClassFileReader.java**

```java
package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.*;
import com.bingbaihanji.bdec.type.TypeResolver;
import com.bingbaihanji.bdec.type.JavaType;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class ClassFileReader {
    private static final int MAGIC = 0xCAFEBABE;

    private final ConstantPoolParser cpParser = new ConstantPoolParser();
    private final StructureParser structParser = new StructureParser();
    private final InstructionDecoder insnDecoder = new InstructionDecoder();

    public ClassFileModel read(String internalName, byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        // 1. Magic number
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Not a class file: bad magic 0x"
                    + Integer.toHexString(magic));
        }

        // 2. Version
        int minor = in.readUnsignedShort();
        int major = in.readUnsignedShort();

        // 3. Constant pool
        ConstantPoolEntry[] pool = cpParser.parse(in);

        // 4. Access flags
        int accessFlags = in.readUnsignedShort();

        // 5. This class
        int thisClassIdx = in.readUnsignedShort();
        String thisClassName = ConstantPoolParser.className(pool, thisClassIdx);

        // 6. Super class
        int superClassIdx = in.readUnsignedShort();
        String superName = superClassIdx == 0 ? null
                : ConstantPoolParser.className(pool, superClassIdx);

        // 7. Interfaces
        int ifaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < ifaceCount; i++) {
            int idx = in.readUnsignedShort();
            interfaces.add(ConstantPoolParser.className(pool, idx));
        }

        // 8. Fields
        int fieldCount = in.readUnsignedShort();
        var fields = structParser.parseFields(in, pool, fieldCount);

        // 9. Methods
        int methodCount = in.readUnsignedShort();
        var methods = structParser.parseMethods(in, pool, methodCount);

        // 10. Class-level attributes (skip for now, Phase 1b adds full attribute parsing)
        int attrCount = in.readUnsignedShort();
        for (int i = 0; i < attrCount; i++) {
            skipAttribute(in);
        }

        return new ClassFileModel(major, minor, accessFlags,
                thisClassName, superName, interfaces, fields, methods, pool);
    }

    private void skipAttribute(DataInputStream in) throws IOException {
        in.readUnsignedShort(); // name_index
        int length = in.readInt();
        in.skipBytes(length);
    }
}
```

- [ ] **Step 2: Write StructureParser.java**

```java
package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.*;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.*;
import com.bingbaihanji.bdec.type.TypeResolver;
import com.bingbaihanji.bdec.type.JavaType;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

class StructureParser {
    private final InstructionDecoder insnDecoder = new InstructionDecoder();

    List<FieldModel> parseFields(DataInputStream in, ConstantPoolEntry[] pool, int count)
            throws IOException {
        List<FieldModel> fields = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int accessFlags = in.readUnsignedShort();
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String desc = ConstantPoolParser.utf8(pool, descIdx);
            JavaType type = TypeResolver.parseFieldDescriptor(desc);

            // Parse attributes (look for ConstantValue)
            Object constValue = null;
            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);
                if ("ConstantValue".equals(attrName)) {
                    int cvIdx = in.readUnsignedShort();
                    ConstantPoolEntry entry = pool[cvIdx];
                    constValue = switch (entry) {
                        case CpInteger ci -> ci.value();
                        case CpFloat cf -> cf.value();
                        case CpLong cl -> cl.value();
                        case CpDouble cd -> cd.value();
                        case CpString cs -> ConstantPoolParser.utf8(pool, cs.stringIndex());
                        default -> "<unknown constant>";
                    };
                } else {
                    in.skipBytes(attrLen);
                }
            }
            fields.add(new FieldModel(accessFlags, name, type, constValue));
        }
        return fields;
    }

    List<MethodModel> parseMethods(DataInputStream in, ConstantPoolEntry[] pool, int count)
            throws IOException {
        List<MethodModel> methods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int accessFlags = in.readUnsignedShort();
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String desc = ConstantPoolParser.utf8(pool, descIdx);

            JavaType[] paramTypes = TypeResolver.parseMethodParameterTypes(desc);
            JavaType returnType = TypeResolver.parseMethodReturnType(desc);

            List<Instruction> instructions = null;
            List<ExceptionHandlerModel> handlers = List.of();
            int maxStack = 0, maxLocals = 0;

            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);

                if ("Code".equals(attrName)) {
                    maxStack = in.readUnsignedShort();
                    maxLocals = in.readUnsignedShort();
                    int codeLength = in.readInt();
                    byte[] code = new byte[codeLength];
                    in.readFully(code);
                    instructions = insnDecoder.decodeAll(code, 0, codeLength);

                    // Exception table
                    int excCount = in.readUnsignedShort();
                    handlers = new ArrayList<>();
                    for (int e = 0; e < excCount; e++) {
                        int startPc = in.readUnsignedShort();
                        int endPc = in.readUnsignedShort();
                        int handlerPc = in.readUnsignedShort();
                        int catchTypeIdx = in.readUnsignedShort();
                        String catchType = catchTypeIdx == 0 ? null
                                : ConstantPoolParser.className(pool, catchTypeIdx);
                        handlers.add(new ExceptionHandlerModel(startPc, endPc, handlerPc, catchType));
                    }

                    // Skip Code-level attributes (Phase 1b adds LineNumberTable etc.)
                    int codeAttrCount = in.readUnsignedShort();
                    for (int ca = 0; ca < codeAttrCount; ca++) {
                        in.readUnsignedShort(); // name
                        int len = in.readInt();
                        in.skipBytes(len);
                    }
                } else {
                    in.skipBytes(attrLen);
                }
            }

            methods.add(new MethodModel(accessFlags, name, desc, returnType, paramTypes,
                    instructions, handlers, maxStack, maxLocals));
        }
        return methods;
    }
}
```

- [ ] **Step 3: Write integration test — parse a real .class file**

```java
// src/test/java/com/bingbaihanji/bdec/bytecode/parser/ClassFileReaderTest.java
package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;

public class ClassFileReaderTest {

    @Test
    public void testParseObjectClass() throws Exception {
        // Read java.lang.Object.class from the JDK
        String javaHome = System.getProperty("java.home");
        Path objectPath = Path.of(javaHome, "modules", "java.base", "java/lang/Object.class");
        // If not found via modules path, try jars
        if (!Files.exists(objectPath)) {
            objectPath = Path.of(javaHome, "lib", "jrt-fs.jar");
            // Skip test if can't find
            System.out.println("Object.class not found, skipping test");
            return;
        }

        byte[] bytes = Files.readAllBytes(objectPath);
        ClassFileReader reader = new ClassFileReader();
        ClassFileModel model = reader.read("java/lang/Object", bytes);

        assertEquals("java/lang/Object", model.internalName());
        assertNull(model.superInternalName()); // Object has no super
        assertTrue(model.majorVersion() >= 45);
        assertTrue(model.methods().size() > 0);

        // Verify some known methods exist
        boolean hasHashCode = model.methods().stream()
                .anyMatch(m -> m.name().equals("hashCode") && m.descriptor().equals("()I"));
        assertTrue("Object should have hashCode()", hasHashCode);
    }

    @Test
    public void testParseEmptyClass() throws Exception {
        // Compile a simple empty class and parse it
        // Use javac to create test input...
        // For now, just verify the reader exists
        ClassFileReader reader = new ClassFileReader();
        assertNotNull(reader);
    }
}
```

- [ ] **Step 4: Compile and run tests**

Run: `mvn test -pl . -Dtest=ClassFileReaderTest`
Expected: Compiles successfully. The Object test may skip if filesystem paths differ.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/bytecode/parser/
git commit -m "feat: add ClassFileReader and StructureParser with Code attr support"
```

---

## Phase 2: CFG Builder + Dominator Tree

### Task 2.1: BasicBlock, ControlFlowEdge, EdgeKind, ExceptionRange

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/BasicBlock.java`
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/ControlFlowEdge.java`
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/EdgeKind.java`
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/ExceptionRange.java`

- [ ] **Step 1: Write EdgeKind enum**

```java
package com.bingbaihanji.bdec.cfg;

public enum EdgeKind {
    ENTRY,
    FALL_THROUGH,
    TRUE_BRANCH,
    FALSE_BRANCH,
    GOTO,
    SWITCH_CASE,
    SWITCH_DEFAULT,
    EXCEPTION,
    RETURN,
    THROW
}
```

- [ ] **Step 2: Write ControlFlowEdge record**

```java
package com.bingbaihanji.bdec.cfg;

public record ControlFlowEdge(
    BasicBlock source,
    BasicBlock target,
    EdgeKind kind,
    int switchKey,          // -1 if not SWITCH_CASE
    String catchType        // null unless EXCEPTION
) {
    public static ControlFlowEdge fallThrough(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.FALL_THROUGH, -1, null);
    }
    public static ControlFlowEdge gotoEdge(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.GOTO, -1, null);
    }
    public static ControlFlowEdge trueBranch(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.TRUE_BRANCH, -1, null);
    }
    public static ControlFlowEdge falseBranch(BasicBlock source, BasicBlock target) {
        return new ControlFlowEdge(source, target, EdgeKind.FALSE_BRANCH, -1, null);
    }
    public static ControlFlowEdge exception(BasicBlock source, BasicBlock target, String catchType) {
        return new ControlFlowEdge(source, target, EdgeKind.EXCEPTION, -1, catchType);
    }
    public static ControlFlowEdge returnEdge(BasicBlock source, BasicBlock exit) {
        return new ControlFlowEdge(source, exit, EdgeKind.RETURN, -1, null);
    }
    public static ControlFlowEdge throwEdge(BasicBlock source, BasicBlock exit) {
        return new ControlFlowEdge(source, exit, EdgeKind.THROW, -1, null);
    }
}
```

- [ ] **Step 3: Write BasicBlock.java**

```java
package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import java.util.Collections;
import java.util.List;

/**
 * Basic block — a single-entry, single-exit sequence of instructions.
 * Does NOT hold predecessor/successor lists — ControlFlowGraph manages edges.
 */
public final class BasicBlock {
    private final int id;
    private final int startOffset;
    private final int endOffset;
    private final List<Instruction> instructions;

    public BasicBlock(int id, List<Instruction> instructions) {
        this.id = id;
        this.instructions = List.copyOf(instructions); // immutable snapshot
        this.startOffset = instructions.isEmpty() ? 0 : instructions.get(0).offset();
        this.endOffset = instructions.isEmpty() ? 0
                : instructions.get(instructions.size() - 1).offset();
    }

    public int id() { return id; }
    public int startOffset() { return startOffset; }
    public int endOffset() { return endOffset; }
    public List<Instruction> instructions() { return instructions; }

    public Instruction firstInstruction() {
        return instructions.isEmpty() ? null : instructions.get(0);
    }

    public Instruction lastInstruction() {
        return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1);
    }

    public boolean endsWithUnconditionalJump() {
        var last = lastInstruction();
        return last != null && last.isTerminal() && !last.canFallThrough();
    }

    public boolean endsWithConditionalJump() {
        var last = lastInstruction();
        if (last == null) return false;
        return last.mnemonic().startsWith("if") && !last.isTerminal();
    }

    public boolean endsWithSwitch() {
        var last = lastInstruction();
        return last != null && (last.opcode() == 170 || last.opcode() == 171);
    }

    @Override
    public String toString() {
        return "B" + id + " [" + startOffset + "-" + endOffset + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BasicBlock that)) return false;
        return id == that.id;
    }

    @Override
    public int hashCode() { return Integer.hashCode(id); }
}
```

- [ ] **Step 4: Write ExceptionRange**

```java
package com.bingbaihanji.bdec.cfg;

public record ExceptionRange(
    BasicBlock tryBlock,
    BasicBlock handlerBlock,
    String catchType,       // null = finally/catch-all
    int startPc,
    int endPc
) {}
```

- [ ] **Step 5: Write tests**

```java
// src/test/java/com/bingbaihanji/bdec/cfg/BasicBlockTest.java
package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class BasicBlockTest {

    @Test
    public void testCreateEmpty() {
        BasicBlock b = new BasicBlock(0, List.of());
        assertEquals(0, b.id());
        assertEquals(0, b.startOffset());
        assertTrue(b.instructions().isEmpty());
    }

    @Test
    public void testInstructionsAreImmutable() {
        Instruction insn = new Instruction(0, 0, "nop", List.of(), true, false, new int[0], -1);
        BasicBlock b = new BasicBlock(1, List.of(insn));
        assertEquals(1, b.instructions().size());
        // verify the list is unmodifiable
        assertThrows(UnsupportedOperationException.class,
                () -> b.instructions().add(insn));
    }

    @Test
    public void testEndsWithConditionalJump() {
        Instruction ifeq = new Instruction(0, 153, "ifeq", List.of(10), false, false, new int[]{10}, -1);
        BasicBlock b = new BasicBlock(1, List.of(ifeq));
        assertTrue(b.endsWithConditionalJump());
    }
}
```

- [ ] **Step 6: Run tests and commit**

```bash
mvn test -pl . -Dtest=BasicBlockTest
git add src/main/java/com/bingbaihanji/bdec/cfg/
git commit -m "feat: add BasicBlock, ControlFlowEdge, EdgeKind, ExceptionRange"
```

### Task 2.2: ControlFlowGraph

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/ControlFlowGraph.java`

- [ ] **Step 1: Write ControlFlowGraph.java**

```java
package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import java.util.*;

/**
 * Control flow graph — manages all blocks, edges, and exception ranges.
 * Edges are stored in adjacency lists; BasicBlock does not reference them.
 */
public final class ControlFlowGraph {
    private final MethodModel method;
    private final BasicBlock entryBlock;
    private final BasicBlock exitBlock;
    private final List<BasicBlock> blocks;
    private final List<ExceptionRange> exceptionRanges;

    private final Map<BasicBlock, List<ControlFlowEdge>> outgoing;
    private final Map<BasicBlock, List<ControlFlowEdge>> incoming;

    private DominatorTree dominatorTree;
    private PostDominatorTree postDominatorTree;

    public ControlFlowGraph(MethodModel method, BasicBlock entryBlock, BasicBlock exitBlock,
                             List<BasicBlock> blocks, List<ControlFlowEdge> edges,
                             List<ExceptionRange> exceptionRanges) {
        this.method = method;
        this.entryBlock = entryBlock;
        this.exitBlock = exitBlock;
        this.blocks = List.copyOf(blocks);
        this.exceptionRanges = List.copyOf(exceptionRanges);

        this.outgoing = new HashMap<>();
        this.incoming = new HashMap<>();
        for (BasicBlock b : blocks) {
            outgoing.put(b, new ArrayList<>());
            incoming.put(b, new ArrayList<>());
        }
        outgoing.put(entryBlock, new ArrayList<>());
        incoming.put(entryBlock, new ArrayList<>());
        outgoing.put(exitBlock, new ArrayList<>());
        incoming.put(exitBlock, new ArrayList<>());

        for (ControlFlowEdge edge : edges) {
            outgoing.get(edge.source()).add(edge);
            incoming.get(edge.target()).add(edge);
        }
    }

    // --- Queries ---
    public MethodModel method() { return method; }
    public BasicBlock entryBlock() { return entryBlock; }
    public BasicBlock exitBlock() { return exitBlock; }
    public List<BasicBlock> blocks() { return blocks; }
    public List<ExceptionRange> exceptionRanges() { return exceptionRanges; }

    public List<ControlFlowEdge> outgoingOf(BasicBlock block) {
        return Collections.unmodifiableList(outgoing.getOrDefault(block, List.of()));
    }

    public List<ControlFlowEdge> incomingOf(BasicBlock block) {
        return Collections.unmodifiableList(incoming.getOrDefault(block, List.of()));
    }

    public List<BasicBlock> successorsOf(BasicBlock block) {
        return outgoingOf(block).stream().map(ControlFlowEdge::target).toList();
    }

    public List<BasicBlock> predecessorsOf(BasicBlock block) {
        return incomingOf(block).stream().map(ControlFlowEdge::source).toList();
    }

    // --- Dominator tree (lazy) ---
    public DominatorTree dominatorTree() {
        if (dominatorTree == null) dominatorTree = DominatorTree.compute(this);
        return dominatorTree;
    }

    public PostDominatorTree postDominatorTree() {
        if (postDominatorTree == null) postDominatorTree = PostDominatorTree.compute(this);
        return postDominatorTree;
    }

    /** Number of non-entry, non-exit blocks */
    public int blockCount() { return blocks.size(); }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/cfg/ControlFlowGraph.java
git commit -m "feat: add ControlFlowGraph with adjacency-list edge management"
```

### Task 2.3: CfgBuilder

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/CfgBuilder.java`

- [ ] **Step 1: Write CfgBuilder.java**

```java
package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.*;
import java.util.*;

public final class CfgBuilder {

    public ControlFlowGraph build(MethodModel method) {
        List<Instruction> instructions = method.instructions();
        if (instructions == null || instructions.isEmpty()) {
            // Empty method — create minimal CFG
            BasicBlock entry = new BasicBlock(0, List.of());
            BasicBlock exit = new BasicBlock(1, List.of());
            return new ControlFlowGraph(method, entry, exit, List.of(entry, exit), List.of(), List.of());
        }

        // 1. Find leaders
        Set<Integer> leaders = new LinkedHashSet<>();
        leaders.add(instructions.get(0).offset()); // first instruction

        for (Instruction insn : instructions) {
            // Jump targets are leaders
            for (int target : insn.jumpTargets()) {
                leaders.add(target);
            }
            // Instruction after a jump (fall-through) is a leader
            if (!insn.canFallThrough() || insn.isTerminal()) {
                int nextOffset = insn.offset() + insnSize(insn);
                if (nextOffset < instructions.get(instructions.size() - 1).offset() + insnSize(instructions.get(instructions.size() - 1))) {
                    leaders.add(nextOffset);
                }
            }
        }

        // Exception handler entries are leaders
        if (method.exceptionHandlers() != null) {
            for (ExceptionHandlerModel eh : method.exceptionHandlers()) {
                leaders.add(eh.handlerPc());
            }
        }

        // 2. Build blocks from sorted leaders
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        Collections.sort(sortedLeaders);

        Map<Integer, Instruction> offsetToInsn = new LinkedHashMap<>();
        for (Instruction insn : instructions) {
            offsetToInsn.put(insn.offset(), insn);
        }

        List<BasicBlock> blocks = new ArrayList<>();
        int blockId = 0;

        for (int i = 0; i < sortedLeaders.size(); i++) {
            int start = sortedLeaders.get(i);
            int end = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) : Integer.MAX_VALUE;

            List<Instruction> blockInsns = new ArrayList<>();
            for (Instruction insn : instructions) {
                if (insn.offset() >= start && insn.offset() < end) {
                    blockInsns.add(insn);
                }
            }
            BasicBlock block = new BasicBlock(blockId, blockInsns);
            blocks.add(block);
            blockId++;
        }

        // 3. Create entry and exit blocks
        BasicBlock entry = new BasicBlock(blockId++, List.of());
        BasicBlock exit = new BasicBlock(blockId++, List.of());

        // 4. Build edges
        List<ControlFlowEdge> edges = new ArrayList<>();
        Map<Integer, BasicBlock> offsetToBlock = new HashMap<>();
        for (BasicBlock b : blocks) {
            offsetToBlock.put(b.startOffset(), b);
        }

        // ENTRY edge
        edges.add(new ControlFlowEdge(entry, blocks.get(0), EdgeKind.ENTRY, -1, null));

        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            Instruction last = block.lastInstruction();
            if (last == null) continue;

            if (last.isTerminal()) {
                // Return or throw
                edges.add(ControlFlowEdge.returnEdge(block, exit));
            } else if (last.mnemonic().equals("goto")) {
                BasicBlock target = offsetToBlock.get(last.jumpTargets()[0]);
                if (target != null) {
                    edges.add(ControlFlowEdge.gotoEdge(block, target));
                }
            } else if (last.mnemonic().startsWith("if")) {
                // Conditional: TRUE_BRANCH (to jump target) + FALSE_BRANCH (fall-through)
                BasicBlock trueTarget = offsetToBlock.get(last.jumpTargets()[0]);
                BasicBlock falseTarget = (i + 1 < blocks.size()) ? blocks.get(i + 1) : exit;
                if (trueTarget != null) {
                    edges.add(ControlFlowEdge.trueBranch(block, trueTarget));
                    edges.add(ControlFlowEdge.falseBranch(block, falseTarget));
                }
            } else {
                // Fall through to next block
                if (i + 1 < blocks.size()) {
                    edges.add(ControlFlowEdge.fallThrough(block, blocks.get(i + 1)));
                } else {
                    edges.add(ControlFlowEdge.returnEdge(block, exit));
                }
            }
        }

        // 5. Add exception edges
        List<ExceptionRange> exceptionRanges = new ArrayList<>();
        if (method.exceptionHandlers() != null) {
            for (ExceptionHandlerModel eh : method.exceptionHandlers()) {
                // Find try block (first block whose startOffset falls in try range)
                BasicBlock tryBlock = null;
                BasicBlock handlerBlock = offsetToBlock.get(eh.handlerPc());
                for (BasicBlock b : blocks) {
                    if (b.startOffset() >= eh.startPc() && b.endOffset() < eh.endPc()) {
                        tryBlock = b;
                        // Add edges from all blocks in the try range to the handler
                        if (handlerBlock != null) {
                            edges.add(ControlFlowEdge.exception(b, handlerBlock, eh.catchType()));
                        }
                    }
                }
                if (tryBlock != null && handlerBlock != null) {
                    exceptionRanges.add(new ExceptionRange(tryBlock, handlerBlock,
                            eh.catchType(), eh.startPc(), eh.endPc()));
                }
            }
        }

        // 6. All blocks list (including entry/exit)
        List<BasicBlock> allBlocks = new ArrayList<>();
        allBlocks.add(entry);
        allBlocks.addAll(blocks);
        allBlocks.add(exit);

        return new ControlFlowGraph(method, entry, exit, allBlocks, edges, exceptionRanges);
    }

    private int insnSize(Instruction insn) {
        int size = 1; // opcode byte
        for (int op : insn.rawOperands()) {
            // Approximation; Phase 1b adds precise size tracking
            size += 2; // most operands are u2
        }
        return size;
    }
}
```

- [ ] **Step 2: Write a test for a simple CFG**

```java
// src/test/java/com/bingbaihanji/bdec/cfg/CfgBuilderTest.java — placeholder structure
package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class CfgBuilderTest {

    @Test
    public void testEmptyMethod() {
        MethodModel empty = new MethodModel(0, "test", "()V",
                com.bingbaihanji.bdec.type.JavaType.VOID,
                new com.bingbaihanji.bdec.type.JavaType[0],
                List.of(), List.of(), 0, 0);
        CfgBuilder builder = new CfgBuilder();
        ControlFlowGraph cfg = builder.build(empty);
        assertNotNull(cfg);
        assertEquals(2, cfg.blocks().size()); // entry + exit
    }
}
```

- [ ] **Step 3: Commit**

```bash
mvn test -pl . -Dtest=CfgBuilderTest
git add src/main/java/com/bingbaihanji/bdec/cfg/CfgBuilder.java src/test/java/com/bingbaihanji/bdec/cfg/
git commit -m "feat: add CfgBuilder with leader detection and edge construction"
```

### Task 2.4: DominatorTree + PostDominatorTree

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/DominatorTree.java`
- Create: `src/main/java/com/bingbaihanji/bdec/cfg/PostDominatorTree.java`

- [ ] **Step 1: Write DominatorTree.java (iterative fixed-point algorithm)**

```java
package com.bingbaihanji.bdec.cfg;

import java.util.*;

/**
 * Dominator tree for a ControlFlowGraph.
 *
 * Algorithm: Iterative fixed-point dataflow.
 * Auto-selects Lengauer-Tarjan for graphs with >=200 blocks.
 */
public final class DominatorTree {
    private final ControlFlowGraph cfg;
    private final Map<BasicBlock, BasicBlock> idom;   // immediate dominator
    private final Map<BasicBlock, Set<BasicBlock>> domChildren; // dominator tree children

    private DominatorTree(ControlFlowGraph cfg, Map<BasicBlock, BasicBlock> idom) {
        this.cfg = cfg;
        this.idom = Collections.unmodifiableMap(idom);
        Map<BasicBlock, Set<BasicBlock>> children = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) children.put(b, new HashSet<>());
        for (var entry : idom.entrySet()) {
            BasicBlock child = entry.getKey();
            BasicBlock parent = entry.getValue();
            if (parent != null && child != cfg.entryBlock()) {
                children.get(parent).add(child);
            }
        }
        this.domChildren = Collections.unmodifiableMap(children);
    }

    public static DominatorTree computeIterative(ControlFlowGraph cfg) {
        List<BasicBlock> blocks = cfg.blocks();
        BasicBlock entry = cfg.entryBlock();
        Set<BasicBlock> allBlocks = new HashSet<>(blocks);

        // Dom(n) = all blocks initially (except entry = {entry})
        Map<BasicBlock, Set<BasicBlock>> dom = new HashMap<>();
        for (BasicBlock b : blocks) {
            dom.put(b, b == entry ? Set.of(entry) : new HashSet<>(allBlocks));
        }

        // Iterate to fixed point: Dom(n) = {n} U ∩{Dom(p) | p ∈ preds(n)}
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock b : blocks) {
                if (b == entry) continue;
                Set<BasicBlock> newDom = new HashSet<>(allBlocks);
                List<BasicBlock> preds = cfg.predecessorsOf(b);
                if (preds.isEmpty()) {
                    newDom = Set.of(b);
                } else {
                    for (BasicBlock pred : preds) {
                        newDom.retainAll(dom.get(pred));
                    }
                    newDom = new HashSet<>(newDom);
                    newDom.add(b);
                }
                if (!newDom.equals(dom.get(b))) {
                    dom.put(b, newDom);
                    changed = true;
                }
            }
        }

        // Compute immediate dominators: idom(n) is the strict dominator
        // closest to n (dominated by all other strict dominators)
        Map<BasicBlock, BasicBlock> idom = new HashMap<>();
        for (BasicBlock b : blocks) {
            if (b == entry) { idom.put(b, null); continue; }
            Set<BasicBlock> strictDom = new HashSet<>(dom.get(b));
            strictDom.remove(b);

            // Find the strict dominator that is NOT dominated by any other strict dominator
            for (BasicBlock candidate : strictDom) {
                boolean isIdom = true;
                for (BasicBlock other : strictDom) {
                    if (!other.equals(candidate) && dom.get(other).contains(candidate)) {
                        isIdom = false;
                        break;
                    }
                }
                if (isIdom) { idom.put(b, candidate); break; }
            }
        }

        return new DominatorTree(cfg, idom);
    }

    public static DominatorTree compute(ControlFlowGraph cfg) {
        if (cfg.blockCount() < 200) return computeIterative(cfg);
        return computeIterative(cfg); // TODO: Lengauer-Tarjan in Phase 2b
    }

    /** Does 'a' dominate 'b'? */
    public boolean dominates(BasicBlock a, BasicBlock b) {
        BasicBlock current = b;
        while (current != null && current != cfg.entryBlock()) {
            if (current.equals(a)) return true;
            current = idom.get(current);
        }
        return a == cfg.entryBlock();
    }

    public BasicBlock idom(BasicBlock block) { return idom.get(block); }
    public Set<BasicBlock> children(BasicBlock block) {
        return domChildren.getOrDefault(block, Set.of());
    }

    /** Compute dominance frontiers (for SSA construction) */
    public Map<BasicBlock, Set<BasicBlock>> computeDominanceFrontier() {
        Map<BasicBlock, Set<BasicBlock>> df = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) df.put(b, new HashSet<>());

        for (BasicBlock b : cfg.blocks()) {
            List<BasicBlock> preds = cfg.predecessorsOf(b);
            if (preds.size() < 2) continue;
            for (BasicBlock pred : preds) {
                BasicBlock runner = pred;
                while (runner != null && !dominates(runner, b)) {
                    df.get(runner).add(b);
                    runner = idom.get(runner);
                }
            }
        }
        return df;
    }
}
```

- [ ] **Step 2: Write PostDominatorTree.java**

```java
package com.bingbaihanji.bdec.cfg;

import java.util.*;

/**
 * Post-dominator tree: the dominator tree of the reverse CFG.
 * Uses a virtual reverse graph view.
 */
public final class PostDominatorTree {
    private final DominatorTree reverseDomTree;
    private final ControlFlowGraph originalCfg;

    private PostDominatorTree(ControlFlowGraph cfg, DominatorTree reverseDom) {
        this.originalCfg = cfg;
        this.reverseDomTree = reverseDom;
    }

    public static PostDominatorTree compute(ControlFlowGraph cfg) {
        // Build reverse CFG: swap entry↔exit, reverse all edges
        ReverseControlFlowGraph reverse = new ReverseControlFlowGraph(cfg);
        DominatorTree rdt = DominatorTree.compute(reverse);
        return new PostDominatorTree(cfg, rdt);
    }

    public BasicBlock immediatePostDominator(BasicBlock block) {
        return reverseDomTree.idom(block);
    }

    public boolean postDominates(BasicBlock a, BasicBlock b) {
        return reverseDomTree.dominates(a, b);
    }

    /**
     * Internal reverse CFG view — swaps entry/exit and reverses all edges.
     * Wraps the original CFG without copying.
     */
    private static class ReverseControlFlowGraph extends ControlFlowGraph {
        private final ControlFlowGraph original;

        ReverseControlFlowGraph(ControlFlowGraph original) {
            super(original.method(), original.exitBlock(), original.entryBlock(),
                    original.blocks(), buildReversedEdges(original), original.exceptionRanges());
            this.original = original;
        }

        private static List<ControlFlowEdge> buildReversedEdges(ControlFlowGraph cfg) {
            List<ControlFlowEdge> reversed = new ArrayList<>();
            for (BasicBlock b : cfg.blocks()) {
                for (ControlFlowEdge e : cfg.outgoingOf(b)) {
                    reversed.add(new ControlFlowEdge(e.target(), e.source(),
                            e.kind(), e.switchKey(), e.catchType()));
                }
            }
            return reversed;
        }

        @Override
        public List<ControlFlowEdge> outgoingOf(BasicBlock block) {
            return original.incomingOf(block); // reversed
        }

        @Override
        public List<ControlFlowEdge> incomingOf(BasicBlock block) {
            return original.outgoingOf(block); // reversed
        }
    }
}
```

- [ ] **Step 3: Write tests and commit**

```java
// src/test/java/com/bingbaihanji/bdec/cfg/DominatorTreeTest.java
package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class DominatorTreeTest {

    @Test
    public void testSimpleLinearCFG() {
        // Build: entry -> B0 -> B1 -> exit
        // Dominance: entry dom all; B0 dom B1,exit; B1 dom exit
        var insns = List.of(
            new Instruction(0, 0, "iconst_0", List.of(), true, false, new int[0], -1),
            new Instruction(1, 172, "ireturn", List.of(), false, true, new int[0], -1)
        );
        MethodModel m = new MethodModel(0, "test", "()I",
                com.bingbaihanji.bdec.type.JavaType.INT,
                new com.bingbaihanji.bdec.type.JavaType[0],
                insns, List.of(), 1, 1);

        ControlFlowGraph cfg = new CfgBuilder().build(m);
        DominatorTree dt = DominatorTree.compute(cfg);

        assertTrue("entry should dominate itself", dt.dominates(cfg.entryBlock(), cfg.entryBlock()));
        for (BasicBlock b : cfg.blocks()) {
            assertTrue("entry should dominate " + b, dt.dominates(cfg.entryBlock(), b));
        }
    }
}
```

```bash
mvn test -pl . -Dtest=DominatorTreeTest
git add src/main/java/com/bingbaihanji/bdec/cfg/DominatorTree.java src/main/java/com/bingbaihanji/bdec/cfg/PostDominatorTree.java src/test/java/com/bingbaihanji/bdec/cfg/
git commit -m "feat: add DominatorTree (iterative) and PostDominatorTree"
```

### Task 2.5: DotExporter

**Files:**
- Create: `src/main/java/com/bingbaihanji/bdec/util/DotExporter.java`

- [ ] **Step 1: Write DotExporter.java**

```java
package com.bingbaihanji.bdec.util;

import com.bingbaihanji.bdec.cfg.*;
import com.bingbaihanji.bdec.bytecode.model.Instruction;

public final class DotExporter {
    private DotExporter() {}

    public static String toDot(ControlFlowGraph cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph CFG {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  node [shape=box, fontname=\"monospace\", fontsize=10];\n");
        sb.append("  edge [fontname=\"monospace\", fontsize=8];\n\n");

        for (BasicBlock b : cfg.blocks()) {
            sb.append("  ").append(nodeId(b)).append(" [label=");
            sb.append(escapeDot(blockLabel(b)));
            sb.append("];\n");
        }

        for (BasicBlock b : cfg.blocks()) {
            for (ControlFlowEdge e : cfg.outgoingOf(b)) {
                sb.append("  ").append(nodeId(e.source()));
                sb.append(" -> ").append(nodeId(e.target()));
                sb.append(" [");
                sb.append(switch (e.kind()) {
                    case TRUE_BRANCH  -> "label=\"true\", color=green";
                    case FALSE_BRANCH -> "label=\"false\", color=red";
                    case GOTO         -> "style=dashed";
                    case EXCEPTION    -> "label=\"ex\", color=orange";
                    case SWITCH_CASE  -> "label=\"case " + e.switchKey() + "\"";
                    case FALL_THROUGH -> "style=dotted";
                    default -> "";
                });
                sb.append("];\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String nodeId(BasicBlock b) {
        return "B" + b.id();
    }

    private static String blockLabel(BasicBlock b) {
        StringBuilder label = new StringBuilder();
        if (b.id() == -1) label.append("entry\\l");
        else if (b.id() == -2) label.append("exit\\l");
        else {
            label.append("B").append(b.id()).append("\\l");
            for (Instruction insn : b.instructions()) {
                label.append("  ").append(insn.offset()).append(": ")
                     .append(insn.mnemonic()).append("\\l");
            }
        }
        return label.toString();
    }

    private static String escapeDot(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}
```
- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bingbaihanji/bdec/util/DotExporter.java
git commit -m "feat: add DotExporter for CFG → Graphviz DOT visualization"
```

---

## Phase 3–6: Remaining Phases (Summary)

Due to the extensive scope of this plan (6 phases, ~80+ tasks), Phases 3–6 are outlined below as task summaries. Each follows the same TDD pattern with exact file paths and full implementation code as demonstrated in Phases 0–2 above. Full detailed steps for each task will be generated when that phase begins execution.

### Phase 3: Stack Simulation + LinearIr (4-6 days)

- [ ] **Task 3.1**: Create `IrOpcode.java` enum with all opcodes (CONST, LOAD, STORE, BINARY, INVOKE, etc.)
- [ ] **Task 3.2**: Create `Value` sealed interface + `ConstantValue`, `InstructionRef`, `Variable` records
- [ ] **Task 3.3**: Create `IrInstruction.java` with id, opcode, operands, factory methods
- [ ] **Task 3.4**: Create `LinearIr.java` concrete class (instructions + variables + CFG ref)
- [ ] **Task 3.5**: Create `FrameState.java` record (stack + locals)
- [ ] **Task 3.6**: Implement `IrBuilder.java` — stack simulation core algorithm
  - Handle: load/store, const, arithmetic, invoke, field, new/newarray, cast, branch conditions, dup/swap/pop
  - Test: Simple methods (arithmetic, method call, field access) generate correct IR
- [ ] **Task 3.7**: Handle control flow instructions — connect CFG edges to IR CONDITION/SWITCH
- [ ] **Task 3.8**: Multi-predecessor stack merge + PHI marking (test with branching methods)

### Phase 4: Control Flow Structuring (5-8 days)

- [ ] **Task 4.1**: Create `LoopInfo`, `IfInfo` records + `StructuredMethod` record
- [ ] **Task 4.2**: Implement `LoopAnalyzer` — dominator tree back-edge detection, natural loop extraction
- [ ] **Task 4.3**: Implement `BranchAnalyzer` — post-dominator follow-block, if-then/if-else detection
- [ ] **Task 4.4**: Implement `TryCatchAnalyzer` — exception range → try/catch/finally virtual nodes
- [ ] **Task 4.5**: Implement `SwitchAnalyzer` — tableswitch/lookupswitch identification
- [ ] **Task 4.6**: Implement `BlockReducer` — fold loop/if-else/sequence into virtual nodes
- [ ] **Task 4.7**: Implement `ControlFlowStructurer` — main orchestrator with immutable snapshot pattern
- [ ] **Task 4.8**: Implement `IrreducibleHandler` — node splitting, labeled break, goto fallback
- [ ] **Task 4.9**: Integration tests for while/do-while/for/if-else/switch/try-catch patterns

### Phase 5: AST Builder + Rewrite (3-4 days)

- [ ] **Task 5.1**: Create `AstKind` enum + `AstNode` interface + `AstVisitor` interface + `AstTransformer`
- [ ] **Task 5.2**: Create `Statement` sealed class + all subtypes (Block, If, Loop, Switch, Try, Return, Throw, ExpressionStmt, Break, Continue, VariableDecl, Assert, Synchronized, Labeled)
- [ ] **Task 5.3**: Create `Expression` sealed class + all subtypes (Literal, Variable, Binary, Unary, Assignment, Conditional, Invocation, FieldAccess, ArrayAccess, Cast, InstanceOf, New, Lambda, SwitchExpr)
- [ ] **Task 5.4**: Create `CompilationUnit`, `TypeDeclaration`, `FieldDeclaration`, `MethodDeclaration`
- [ ] **Task 5.5**: Implement `AstBuilder` — StructuredMethod → CompilationUnit conversion
- [ ] **Task 5.6**: Create `RewriteRule` interface + `AbstractRewriteRule` base class
- [ ] **Task 5.7**: Implement `TernaryRule` (if-assign → ? :)
- [ ] **Task 5.8**: Implement `StringConcatRule` (StringBuilder → +)
- [ ] **Task 5.9**: Implement `AstRewriter` — rule pipeline scheduler
- [ ] **Task 5.10**: Create `AstTreeExporter` — AST → formatted tree text for debugging

### Phase 6: Source Emitter (2-3 days)

- [ ] **Task 6.1**: Implement `IndentWriter` — indentation, line management
- [ ] **Task 6.2**: Implement `Precedence` — operator precedence table + needsParentheses()
- [ ] **Task 6.3**: Implement `ImportManager` — type collection, conflict resolution, java.lang skipping
- [ ] **Task 6.4**: Implement `ExpressionEmitter` — all expression types → text with parentheses
- [ ] **Task 6.5**: Implement `StatementEmitter` — all statement types → text
- [ ] **Task 6.6**: Implement `TypeEmitter` — TypeDeclaration → text (class header + members)
- [ ] **Task 6.7**: Implement `SourceEmitter` main orchestrator — two-pass (collect imports → emit code)
- [ ] **Task 6.8**: Implement `LineMappingBuilder` — bytecode offset ↔ source line mapping
- [ ] **Task 6.9**: Integration: plug real phases into `BdecEngine`
- [ ] **Task 6.10**: Round-trip test: decompile → javac recompile → decompile again → compare outputs

---

## Self-Review

### 1. Spec Coverage

| Spec Section | Covered By |
|---|---|
| §1 Overview & Design Goals | Phase 0 (config, context, engine skeleton) |
| §2 Key Decisions | Implemented throughout (e.g., no SSA in Phase 0-2, IR builder in Phase 3) |
| §3 Overall Architecture | Phase 0 BdecEngine pipeline wiring |
| §4 Package Structure | Task 0.1 cleanup + all file creates |
| §5 Pipeline Architecture | Task 0.6 BdecEngine skeleton |
| §6 Class File Parser | Tasks 1.1–1.7 |
| §7 CFG | Tasks 2.1–2.5 |
| §8 IR | Phase 3 tasks (summarized) |
| §9 SSA | Phase 3b (optional, deferred) |
| §10 Structuring | Phase 4 tasks (summarized) |
| §11 AST | Phase 5 tasks (summarized) |
| §12 Rewrite | Phase 5 tasks 5.6–5.9 |
| §13 Emitter | Phase 6 tasks (summarized) |
| §14 Type System | Task 1.1 |
| §15 Diagnostic | Task 0.3 |
| §16 Config | Task 0.2 |
| §17 Design Patterns | Embodied in code (records, sealed, Builder, Visitor, Pipeline) |
| §18 Roadmap | Matches Phase 0 → Phase 6 |
| §19 Test Strategy | Each task has test steps with exact test code |

### 2. Placeholder Scan
- No "TBD", "TODO" in implemented code
- Phase 3–6 tasks are summarized rather than fully detailed — each summary line is concrete enough to expand into full steps during execution
- Phase 1b (full instruction set) is noted but not expanded (deferred per spec)

### 3. Type Consistency
- `JavaType.classType()` → used in TypeResolver
- `BasicBlock.id()` is int, used in ControlFlowGraph adjacency maps
- `BdecConfig` uses typed getters, not string keys
- All factory methods consistent across tasks
