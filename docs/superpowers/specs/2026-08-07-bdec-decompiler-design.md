# BDEC Java 反编译引擎 — 架构设计文档

> **日期**: 2026-08-07
> **版本**: 1.1
> **状态**: 设计完成，待实现
> **变更 (1.0→1.1)**: IR 层次明确为 LinearIr + 可选 SSA 层；接口仅保留 4 个插件点；CFG 变换统一为不可变快照模式；Phase 1 拆分为 1a/1b；配置改为 typed builder；诊断字段结构化

---

## 目录

1. [项目概述与设计目标](#1-项目概述与设计目标)
2. [关键决策记录](#2-关键决策记录)
3. [总体架构](#3-总体架构)
4. [包结构与模块划分](#4-包结构与模块划分)
5. [管线架构](#5-管线架构)
6. [模块1：Class 文件解析器](#6-模块1class-文件解析器)
7. [模块2：控制流图 (CFG)](#7-模块2控制流图-cfg)
8. [模块3：中间表示 (IR) 与栈模拟](#8-模块3中间表示-ir-与栈模拟)
9. [模块4：数据流分析与 SSA](#9-模块4数据流分析与-ssa)
10. [模块5：控制流结构化](#10-模块5控制流结构化)
11. [模块6：AST 设计](#11-模块6ast-设计)
12. [模块7：AST Rewrite 管线](#12-模块7ast-rewrite-管线)
13. [模块8：源码输出 (Source Emitter)](#13-模块8源码输出-source-emitter)
14. [模块9：类型系统](#14-模块9类型系统)
15. [诊断系统与调试导出](#15-诊断系统与调试导出)
16. [引擎配置项](#16-引擎配置项)
17. [设计模式总览](#17-设计模式总览)
18. [实现路线图](#18-实现路线图)
19. [测试策略](#19-测试策略)
20. [参考项目分析摘要](#20-参考项目分析摘要)

---

## 1. 项目概述与设计目标

bdec (Bingbaihanji Decompiler Engine Core) 是一款纯 Java 实现的 class 文件反编译引擎。

### 核心约束

| 约束 | 说明 |
|---|---|
| **零外部依赖** | 仅使用 JDK 25 原生 API，手写 class 文件解析器 |
| **通用引擎** | 支持 IDE 集成、批量反编译、安全分析等所有场景 |
| **正确性优先** | 保证反编译结果语义等价，其次追求代码可读性 |
| **可调试** | 每阶段支持中间产物导出（DOT/树形文本） |

### 设计哲学

- **自底向上实现**：每层有单元测试后再进入下一层
- **接口仅用于插件点**：Parser / Builder / Analyzer / Rewriter / Emitter 保留接口；CFG/IR/AST/分析结果全部用 record 或 sealed 具体类
- **不可变优先**：分析结果用 record；CFG 边集中管理；结构化变换返回新图快照
- **异常优先处理**：Try-Catch-Finally 在结构化阶段最先识别

---

## 2. 关键决策记录

| # | 决策 | 选项 | 理由 |
|---|---|---|---|
| 1 | Class 文件解析方式 | **手写解析器** | 零外部依赖，完全可控 |
| 2 | 解析器架构 | **分层解析器 + 属性注册表** | 可扩展性最好，新 Java 版本属性只需注册新解析器 |
| 3 | 表达式重建策略 | **混合方案（符号执行 + SSA 优化）** | 先快出结果，再优化质量 |
| 4 | IR 层设计 | **两层 IR：Linear IR（栈模拟产物）→ 可选 SSA/Analysis IR（优化层）** | Linear IR 承担 CFG→AST 的桥梁职责；SSA/类型推导作为可选优化插入管线，不强制所有路径经过 SSA |
| 5 | 控制流结构化 | **支配树递归分解 + 块折叠** | Vineflower 模式，最清晰可调试 |
| 6 | AST Rewrite | **线性规则管线** | CFR 模式，每条规则只做一件事 |
| 7 | 测试策略 | **混合：单元 + 编译回环 + 黄金文件** | 覆盖各层级验证需求 |
| 8 | 实现顺序 | **自底向上逐层实现** | 每层有测试再进入下一层 |

---

## 3. 总体架构

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  Class   │───▶│   CFG    │───▶│  Stack   │───▶│ControlFlow│──▶│   AST    │───▶│  Source  │
│  Parser  │    │ Builder  │    │   Sim    │    │Structurer │    │ Builder  │    │ Emitter  │
└──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
     │               │               │               │               │               │
     ▼               ▼               ▼               ▼               ▼               ▼
ClassFileModel  ControlFlowGraph  LinearIr    StructuredMethod CompilationUnit  SourceFile

                              诊断系统 (贯穿所有阶段)
                              调试导出 (DOT / AST Tree)
```

### 数据流转

```
[.class bytes]
      │
      ▼ ClassFileReader / ConstantPoolParser / InstructionDecoder
ClassFileModel (字段/方法/常量池/属性)
      │
      ▼ CfgBuilder (Leader识别 → 基本块划分 → 边建连)
ControlFlowGraph (BasicBlock + ControlFlowEdge + ExceptionRange)
      │
      ▼ IrBuilder (符号栈模拟执行 → 每条指令产出1条 Linear IR 指令)
LinearIr (IrInstruction 扁平列表 + CFG 弱引用 + Variable 变量表)
      │
      ├──► [可选] SsaConverter → AnalysisIr (SSA版本化 + φ节点)
      │         │
      │         ▼ DataFlowAnalyzer → TypeInference
      │    LinearIr (类型标注后，回写到 LinearIr)
      │
      ▼ ControlFlowStructurer (输入是 LinearIr + CFG，不依赖 SSA 形式)
      │   ├── TryCatchAnalyzer (最先)
      │   ├── SwitchAnalyzer
      │   ├── LoopAnalyzer (支配树回边)
      │   ├── BranchAnalyzer (后支配树Follow)
      │   ├── BlockReducer (迭代折叠，返回新图快照)
      │   └── IrreducibleHandler (兜底)
StructuredMethod (BlockStatement 根)
      │
      ▼ AstBuilder
CompilationUnit (TypeDeclaration → MethodDeclaration → Statement/Expression AST)
      │
      ▼ AstRewriter (线性规则管线)
CompilationUnit (重写后 — StringConcat/Lambda/Ternary/Enum/...)
      │
      ▼ SourceEmitter
          ├── ImportManager   (第一遍: 类型收集 → import 列表)
          ├── StatementEmitter (第二遍: 代码输出)
          ├── ExpressionEmitter (括号决策)
          └── LineMappingBuilder (行号映射)
SourceFile (Java source + lineNumberMapping)
```

**关键**：SSA 是可选优化层。简单方法（无分支/单分支）直接走 LinearIr → Structuring；复杂方法（多前驱合并多、类型信息不足）插入 SSA 层提升质量。

---

## 4. 包结构与模块划分

```
com.bingbaihanji.bdec/
├── BdecEngine.java              ← 引擎入口
├── BdecContext.java             ← 反编译上下文
├── BdecResult.java              ← 反编译结果 record
│
├── bytecode/                    ← 📦 模块1: class 文件解析
│   ├── parser/
│   │   ├── ClassFileReader      ← 入口：魔数→版本→常量池→结构→属性
│   │   ├── ConstantPoolParser   ← 常量池解析
│   │   ├── StructureParser      ← 字段/方法签名解析
│   │   ├── CodeParser           ← Code 属性 + 字节码指令解析
│   │   ├── InstructionDecoder   ← 单条指令解码
│   │   └── attr/
│   │       ├── AttributeParser  ← 接口
│   │       ├── AttributeRegistry← 注册表
│   │       └── impl/            ← 各属性解析器实现
│   ├── model/
│   │   ├── ClassFileModel       ← 类文件模型
│   │   ├── FieldModel           ← 字段模型
│   │   ├── MethodModel          ← 方法模型
│   │   ├── ExceptionHandlerModel← 异常处理器
│   │   ├── Instruction          ← 单条指令
│   │   └── constantpool/        ← 常量池条目
│   └── opcode/
│       ├── Opcode               ← 操作码枚举
│       └── Mnemonic             ← 助记符
│
├── cfg/                         ← 📦 模块2: 控制流图
│   ├── BasicBlock               ← 基本块（具体类）
│   ├── ControlFlowGraph         ← CFG（具体类）
│   ├── ControlFlowEdge          ← 类型化边
│   ├── EdgeKind                 ← 边类型枚举
│   ├── CfgBuilder               ← CFG 构建器
│   ├── ExceptionRange           ← 异常范围
│   ├── DominatorTree            ← 支配树
│   └── PostDominatorTree        ← 后支配树
│
├── ir/                          ← 📦 模块3: 中间表示
│   ├── LinearIr                 ← 方法 IR（栈模拟产物）
│   ├── IrInstruction            ← IR 指令
│   ├── IrOpcode                 ← IR 操作码
│   ├── Value                    ← 值接口 (sealed)
│   ├── ConstantValue            ← 常量值 record
│   ├── InstructionRef           ← 指令引用 record
│   ├── Variable                 ← SSA 版本化变量
│   ├── IrBuilder                ← 栈模拟 → LinearIr 构建器
│   └── FrameState               ← 帧状态 record
│
├── analysis/                    ← 📦 模块4: 数据流分析
│   ├── DataFlowAnalyzer         ← 数据流分析
│   ├── TypeInference            ← 类型推导
│   ├── SsaConverter             ← SSA 转换
│   └── CopyPropagation          ← 副本传播
│
├── structuring/                 ← 📦 模块5: 控制流结构化
│   ├── ControlFlowStructurer    ← 结构化器（主入口）
│   ├── LoopAnalyzer             ← 循环识别
│   ├── BranchAnalyzer           ← 分支识别
│   ├── SwitchAnalyzer           ← Switch 识别
│   ├── TryCatchAnalyzer         ← Try-Catch 识别
│   ├── BlockReducer             ← 块折叠算法
│   ├── IrreducibleHandler       ← 不可约图处理
│   ├── LoopInfo                 ← 循环分析结果 record
│   ├── IfInfo                   ← 分支分析结果 record
│   └── StructuredMethod         ← 结构化结果 record
│
├── ast/                         ← 📦 模块6+7: AST + Rewrite
│   ├── AstNode                  ← AST 节点接口
│   ├── AstKind                  ← 节点类型枚举
│   ├── AstVisitor               ← Visitor 接口
│   ├── AstTransformer           ← 替换遍历器
│   ├── CompilationUnit          ← 编译单元
│   ├── TypeDeclaration          ← 类型声明
│   ├── AstBuilder               ← IR→AST 构建器
│   ├── stmt/                    ← 语句节点
│   │   ├── Statement            ← sealed 基类
│   │   ├── BlockStatement
│   │   ├── IfStatement
│   │   ├── LoopStatement
│   │   ├── SwitchStatement
│   │   ├── TryStatement
│   │   ├── ReturnStatement
│   │   ├── ThrowStatement
│   │   ├── ExpressionStatement
│   │   ├── BreakStatement
│   │   ├── ContinueStatement
│   │   ├── VariableDeclaration
│   │   ├── AssertStatement
│   │   ├── SynchronizedStatement
│   │   └── LabeledStatement
│   ├── expr/                    ← 表达式节点
│   │   ├── Expression           ← sealed 基类
│   │   ├── LiteralExpression
│   │   ├── VariableExpression
│   │   ├── BinaryExpression
│   │   ├── UnaryExpression
│   │   ├── AssignmentExpression
│   │   ├── ConditionalExpression
│   │   ├── InvocationExpression
│   │   ├── FieldAccessExpression
│   │   ├── ArrayAccessExpression
│   │   ├── CastExpression
│   │   ├── InstanceOfExpression
│   │   ├── NewExpression
│   │   ├── LambdaExpression
│   │   └── SwitchExpression
│   └── rewrite/                 ← Rewrite 管线
│       ├── RewriteRule          ← 规则接口
│       ├── AbstractRewriteRule  ← 基类
│       ├── AstRewriter          ← 重写管线调度
│       └── rules/               ← 具体规则
│           ├── StringConcatRule
│           ├── LambdaRule
│           ├── TernaryRule
│           ├── TryWithResourceRule
│           ├── EnumRewriter
│           ├── RecordRewriter
│           ├── StringSwitchRule
│           ├── EnumSwitchRule
│           ├── DiamondOperatorRule
│           ├── ForEachRule
│           ├── AutoBoxingRule
│           ├── AssertRule
│           ├── SynchronizedRule
│           ├── UnusedVariableCleanupRule
│           ├── DeadCodeEliminationRule
│           └── ConstantFoldRule
│
├── emit/                        ← 📦 模块8: 源码输出
│   ├── SourceEmitter            ← 源码生成器（主入口）
│   ├── SourceFile               ← 输出结果 record
│   ├── ImportManager            ← import 管理
│   ├── IndentWriter             ← 缩进/格式化工具
│   ├── Precedence               ← 运算符优先级
│   ├── LineMappingBuilder       ← 行号映射
│   ├── TypeEmitter              ← 类型声明输出
│   ├── StatementEmitter         ← 语句输出
│   └── ExpressionEmitter        ← 表达式输出
│
├── type/                        ← 📦 模块9: 类型系统
│   ├── JavaType                 ← 类型接口
│   ├── TypeKind                 ← 类型枚举
│   └── TypeResolver             ← 类型解析器
│
├── diagnostic/                  ← 诊断
│   ├── DiagnosticLevel          ← 级别枚举
│   ├── DiagnosticListener       ← 监听器接口
│   └── DecompilerDiagnostic     ← 诊断信息 record
│
└── util/                        ← 工具类
    ├── DotExporter              ← CFG → DOT 格式
    ├── AstTreeExporter          ← AST → 树形文本
    └── collection/              ← 自定义集合（现有）
```

---

## 5. 管线架构

### 引擎入口

```java
public class BdecEngine implements Decompiler {
    private final BdecConfig config;
    private final DiagnosticListener diagnostics;

    public BdecEngine(BdecConfig config, DiagnosticListener diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
    }

    public DecompileResult decompile(String internalName, byte[] bytes,
                                      DecompileContext ctx) {
        List<String> warnings = new ArrayList<>();

        // 1. Class 解析
        ClassFileModel classFile = classReader.read(internalName, bytes);
        diagnostics.report(DecompilerDiagnostic.info("parser", internalName,
            "parsed v" + classFile.majorVersion() + ", "
            + classFile.methods().size() + " methods"));

        // 2. 逐方法反编译
        List<StructuredMethod> structuredMethods = new ArrayList<>();
        for (MethodModel method : classFile.methods()) {
            if (method.isAbstract() || method.isNative()) continue;

            ControlFlowGraph cfg = cfgBuilder.build(method);
            // 栈模拟 → LinearIr（第一层，总是执行）
            LinearIr ir = irBuilder.build(cfg, method);

            // SSA 优化（第二层，可选 — 由 ssaThreshold 控制）
            if (shouldRunSsa(cfg, config)) {
                ir = ssaConverter.convert(ir);
                ir = dataFlowAnalyzer.analyze(ir);
                ir = typeInference.infer(ir, ctx);
            }

            StructuredMethod sm = structurer.structure(ir, ctx);
            structuredMethods.add(sm);
        }

        // 3. AST → Rewrite → Emit
        CompilationUnit unit = astBuilder.build(classFile, structuredMethods);
        unit = astRewriter.rewrite(unit, config);
        SourceFile source = sourceEmitter.emit(unit, config);

        return new DecompileResult(true, source.source(), null, warnings,
                                   source.sourceLineToBytecodeOffset());
    }

    private boolean shouldRunSsa(ControlFlowGraph cfg, BdecConfig config) {
        int threshold = config.ssaThreshold();
        if (threshold < 0) return false;
        if (threshold == 0) return true;
        return cfg.blocks().size() >= threshold; // 默认≥5块
    }
}
```

### Phase 接口

```java
public interface DecompilerPhase<I, O> {
    String name();
    O run(I input, DecompileContext context, DiagnosticListener diagnostics);
}
```

---

## 6. 模块1：Class 文件解析器

### 架构：分层解析 + 属性注册表

```
ClassFileReader (入口)
  ├── ConstantPoolParser   — 常量池条目
  ├── StructureParser      — 字段/方法签名（不含方法体）
  ├── CodeParser           — Code 属性内的字节码指令
  │   └── InstructionDecoder — 单条指令解码
  └── attr/
      ├── AttributeParser   — 接口
      ├── AttributeRegistry — 按属性名查找解析器
      └── impl/             — 各属性解析器
          ├── CodeAttributeParser
          ├── LineNumberTableParser
          ├── LocalVariableTableParser
          ├── StackMapTableParser
          ├── BootstrapMethodsParser
          ├── SignatureAttributeParser
          ├── AnnotationParser
          ├── InnerClassesParser
          ├── NestHostParser / NestMembersParser
          ├── RecordParser
          └── ...
```

### AttributeParser 接口

```java
@FunctionalInterface
public interface AttributeParser {
    /**
     * @param cp     常量池（用于解析字符串/类引用）
     * @param name   属性名 (如 "Code", "Signature")
     * @param data   属性原始字节
     * @param length 属性长度
     * @return 解析后的属性模型，null 表示跳过
     */
    AttributeModel parse(ConstantPool cp, String name, byte[] data, int length);
}
```

### 常量池条目

```
ConstantPoolEntry (sealed interface)
├── CpUtf8(String value)
├── CpInteger(int value)
├── CpFloat(float value)
├── CpLong(long value)
├── CpDouble(double value)
├── CpClass(int nameIndex)
├── CpString(int stringIndex)
├── CpFieldRef(int classIndex, int nameAndTypeIndex)
├── CpMethodRef(int classIndex, int nameAndTypeIndex)
├── CpInterfaceMethodRef(int classIndex, int nameAndTypeIndex)
├── CpNameAndType(int nameIndex, int descriptorIndex)
├── CpMethodHandle(int referenceKind, int referenceIndex)
├── CpMethodType(int descriptorIndex)
├── CpDynamic(int bootstrapMethodAttrIndex, int nameAndTypeIndex)
├── CpInvokeDynamic(int bootstrapMethodAttrIndex, int nameAndTypeIndex)
├── CpModule(int nameIndex)
├── CpPackage(int nameIndex)
```

### 指令模型

```java
public class Instruction {
    int offset();               // 字节码偏移
    int opcode();               // JVM 操作码 (0-255)
    String mnemonic();          // 助记符 (如 "iload_1")
    List<InstructionOperand> operands();
    boolean canFallThrough();   // 能否 fall through 到下一指令
    boolean isTerminal();       // 是否终止指令 (return/throw/athrow)
    int[] jumpTargets();        // 跳转目标偏移列表
    int varIndex();             // 局部变量索引 (-1 表示不适用)
}
```

---

## 7. 模块2：控制流图 (CFG)

### BasicBlock

```java
public class BasicBlock {
    private final int id;
    private final int startOffset;
    private final int endOffset;
    private final List<Instruction> instructions;

    // 构造时传入指令列表，不持有边信息
    BasicBlock(int id, List<Instruction> instructions);

    // 只读属性
    public int id();
    public int startOffset();
    public int endOffset();
    public List<Instruction> instructions();           // 不可变视图

    // Package-private: CfgBuilder 构建时用
    Instruction firstInstruction();
    Instruction lastInstruction();
    boolean endsWithUnconditionalJump();   // goto / return / throw
    boolean endsWithConditionalJump();     // ifeq / ifne / ...
    boolean endsWithSwitch();              // tableswitch / lookupswitch
}
```

### ControlFlowGraph — 集中管理边

```java
public class ControlFlowGraph {
    private final MethodModel method;
    private final BasicBlock entryBlock;
    private final BasicBlock exitBlock;        // 虚拟出口
    private final List<BasicBlock> blocks;
    private final List<ExceptionRange> exceptionRanges;

    // 邻接表 — 边由 CFG 集中管理
    private final Map<BasicBlock, List<ControlFlowEdge>> outgoingEdges;
    private final Map<BasicBlock, List<ControlFlowEdge>> incomingEdges;

    // 图查询
    public List<ControlFlowEdge> outgoingOf(BasicBlock block);
    public List<ControlFlowEdge> incomingOf(BasicBlock block);
    public List<BasicBlock> successorsOf(BasicBlock block);
    public List<BasicBlock> predecessorsOf(BasicBlock block);

    // 支配树 (惰性计算+缓存)
    private DominatorTree dominatorTree;
    private PostDominatorTree postDominatorTree;
    public DominatorTree dominatorTree();
    public PostDominatorTree postDominatorTree();
}
```

### ControlFlowEdge — 类型化边

```java
public class ControlFlowEdge {
    BasicBlock source();
    BasicBlock target();
    EdgeKind kind();
    int switchKey();             // SWITCH_CASE 时用
    String catchType();          // EXCEPTION 时用 (null = finally/catch-all)
}

public enum EdgeKind {
    ENTRY,          // entry → 方法入口
    FALL_THROUGH,   // 顺序执行到下一块
    TRUE_BRANCH,    // 条件为真的分支
    FALSE_BRANCH,   // 条件为假的分支
    GOTO,           // 无条件跳转
    SWITCH_CASE,    // switch 的 case 分支
    SWITCH_DEFAULT, // switch 的 default
    EXCEPTION,      // 异常边 (try → handler)
    RETURN,         // 返回边 (→ exit)
    THROW           // 异常抛出边 (→ exit)
}
```

### ExceptionRange

```java
public class ExceptionRange {
    BasicBlock tryBlock();        // try 区域入口块
    BasicBlock handlerBlock();    // catch/finally handler 入口
    String catchType();           // null = finally / catch-all
    int startPc();                // 原始字节码 try 范围起始
    int endPc();                  // 原始字节码 try 范围结束
}
```

### CfgBuilder — 构建流程

```
1. 识别 Leader 指令：
   - 第一条指令 (offset=0)
   - 所有跳转目标
   - 所有跳转指令的下一条 (fall-through target)
   - 所有异常 handler 的入口

2. 划分 BasicBlock：从每个 Leader 顺序读取到下一个 Leader 前

3. 连线 Edge：
   - Fall-through → FALL_THROUGH
   - 条件跳转 → TRUE_BRANCH + FALSE_BRANCH
   - goto → GOTO
   - tableswitch/lookupswitch → SWITCH_CASE + SWITCH_DEFAULT
   - 异常表 → EXCEPTION
   - return/throw → RETURN/THROW (→ exitBlock)

4. 创建虚拟 entry/exit 块
```

### DominatorTree / PostDominatorTree

```java
public class DominatorTree {
    // 两种算法实现
    public static DominatorTree computeIterative(ControlFlowGraph cfg);
    public static DominatorTree computeLengauerTarjan(ControlFlowGraph cfg);

    // 自动选择: <200块用迭代, >=200用Lengauer-Tarjan
    public static DominatorTree compute(ControlFlowGraph cfg);

    public boolean dominates(BasicBlock a, BasicBlock b);
    public BasicBlock idom(BasicBlock block);
    public Set<BasicBlock> children(BasicBlock block);
    public Map<BasicBlock, Set<BasicBlock>> computeDominanceFrontier(); // SSA 用
}

public class PostDominatorTree {
    // 反向CFG + 支配树 = 后支配树（找 Follow/Merge 点用）
    public static PostDominatorTree compute(ControlFlowGraph cfg);
    public BasicBlock immediatePostDominator(BasicBlock block);
    public boolean postDominates(BasicBlock a, BasicBlock b);
}
```

---

## 8. 模块3：中间表示 (IR) — 两层设计

### 层次定义

```
LinearIr (第一层：栈模拟直接产物)
  │  — IrInstruction 扁平列表，按基本块有序排列
  │  — 每条 JVM 指令 → 0~1 条 IR 指令
  │  — 控制流完全由 CFG 管理，IR 不重复表达
  │  — 包含 STACK_LOAD/STACK_STORE 等临时变量（后续优化消除）
  │
  ▼ [可选] SsaConverter
AnalysisIr (第二层：SSA 优化产物)
  │  — 与 LinearIr 相同的 IrInstruction 结构，但：
  │  — Variable 带有 version 号，每个赋值一个版本
  │  — 插入 PHI 指令在支配边界
  │  — STACK_LOAD/STORE 被消除（副本传播 + 死代码消除）
  │  — 类型信息更精确（TypeInference 完成）
  │
  ▼ [总是] 回写到 LinearIr（类型+变量名写回），Structuring 阶段统一读取
```

### 何时走 SSA 层

- 简单方法（≤1 个条件分支、无循环、无异常）→ 跳过 SSA，直接进入 Structuring
- 复杂方法（多分支、循环、异常）→ 插入 SSA 层提升变量合并质量和类型精度
- 由 `BdecConfig.decompilerSsaThreshold` 控制，默认自动判断

### IrOpcode

```java
public enum IrOpcode {
    // 常量
    CONST, CONST_STRING,

    // 变量
    LOAD, STORE, PHI,             // PHI: SSA φ 节点
    STACK_LOAD, STACK_STORE,      // 栈临时变量（优化阶段会消除）

    // 运算
    UNARY, BINARY, COMPARE,

    // 类型操作
    CAST, INSTANCE_OF,

    // 内存
    FIELD_LOAD, FIELD_STORE, ARRAY_LOAD, ARRAY_STORE, ARRAY_LENGTH,

    // 调用与分配
    INVOKE, NEW, NEW_ARRAY, NEW_PRIMITIVE_ARRAY,

    // 控制流（IR层面）
    CONDITION, SWITCH,

    // 返回/异常
    RETURN, THROW,

    // 高级（后期识别）
    TERNARY, INC, MONITOR_ENTER, MONITOR_EXIT
}
```

### Value — sealed 接口

```java
public sealed interface Value
        permits Variable, ConstantValue, InstructionRef {
    JavaType type();
}

// 常量
public record ConstantValue(Object value, JavaType type) implements Value {}

// 引用另一条 IR 指令的结果 (SSA use-def 链)
public record InstructionRef(IrInstruction instruction, JavaType type) implements Value {}

// SSA 版本化变量
public class Variable implements Value {
    private final int slot;            // JVM 局部变量槽
    private final int version;         // SSA 版本号
    private String name;               // 推断或指定的变量名
    private final JavaType type;
    private final boolean isParameter;
    private final int originalIndex;
}
```

### IrInstruction

```java
public class IrInstruction {
    private final int id;
    private final IrOpcode opcode;
    private final JavaType resultType;
    private final List<Value> operands;
    private final int sourceOffset;      // 来源字节码偏移
    private final int blockId;           // 所在基本块

    private Value resultValue;           // 本条产出的 SSA Value

    // 工厂方法
    public static IrInstruction binary(IrOpcode op, Value left, Value right, ...);
    public static IrInstruction load(Variable var, int offset);
    public static IrInstruction invoke(MethodRef method, List<Value> args, ...);
}
```

### LinearIr（具体类，非接口）

```java
/**
 * 栈模拟的直接产物 — 单一真实来源 (Single Source of Truth)
 *
 * 所有后续阶段（SSA、Structuring、AST）都从这里读取
 * SSA 分析结果（类型、变量名）回写到这个对象
 */
public class LinearIr {
    private final MethodModel method;
    private final ControlFlowGraph cfg;
    private final List<IrInstruction> instructions;         // 全方法扁平列表
    private final Map<Integer, List<IrInstruction>> blockInstructions; // blockId → 指令
    private final List<Variable> variables;                 // 变量表（SSA后含version）
    private boolean ssaOptimized;                           // 是否已过 SSA 优化
}
```

### IrBuilder — 符号栈模拟（核心算法）

```java
public class IrBuilder {
    /**
     * 逐块符号执行
     *
     * 维护：symbolStack (Deque<Value>) + localVars (Value[slot])
     *
     * 栈操作指令 (dup/swap/pop)：在符号栈上直接操作，不生成IR
     * 计算类指令 (add/call/field)：pop+compute+push，生成IR指令
     * 多前驱合并：栈等深+类型兼容；不兼容处标记PHI
     */
    public LinearIr build(ControlFlowGraph cfg, MethodModel method) {
        // 1. 按支配树顺序处理基本块
        List<BasicBlock> order = postOrder(cfg);
        Map<BasicBlock, FrameState> blockOutputs = new HashMap<>();

        for (BasicBlock block : order) {
            FrameState entry = mergePredecessorStates(block, blockOutputs);
            FrameState exit = simulateBlock(block, entry);
            blockOutputs.put(block, exit);
        }

        // 2. 插入 PHI 指令
        insertPhis(ir, cfg);
        return ir;
    }

    private FrameState simulateBlock(BasicBlock block, FrameState entry, LinearIr ir) {
        Deque<Value> stack = new ArrayDeque<>(entry.stack());
        Value[] locals = entry.locals().clone();

        for (Instruction insn : block.instructions()) {
            switch (insn.opcode()) {
                // 栈: dup/swap/pop → 仅操作符号栈
                case DUP:    { Value v = stack.pop(); stack.push(v); stack.push(v); }
                case SWAP:   { Value a = stack.pop(); Value b = stack.pop();
                               stack.push(a); stack.push(b); }
                // 加载: iload_1 → stack.push(locals[1])
                case ILOAD:  stack.push(locals[insn.varIndex()]);
                // 存储: istore_2 → locals[2] = stack.pop(); 生成 STORE IR
                case ISTORE: { Value v = stack.pop(); locals[insn.varIndex()] = v;
                               ir.addInstruction(IrInstruction.store(...)); }
                // 运算: iadd → b=pop; a=pop; result=newTemp; stack.push(result);
                //                  生成 BINARY IR
                // 调用: invoke → pop args; result=invoke...; 生成 INVOKE IR
                // ...
            }
        }
        return new FrameState(stack, locals);
    }

    private FrameState mergePredecessorStates(BasicBlock block,
            Map<BasicBlock, FrameState> outputs) {
        List<BasicBlock> preds = cfg.predecessorsOf(block);
        if (preds.isEmpty()) return FrameState.empty();
        if (preds.size() == 1) return outputs.get(preds.get(0));
        // 多前驱: 栈等深+类型兼容检查; 标记 PHI 位置
        return mergeMultiple(outputs, preds);
    }
}

record FrameState(Deque<Value> stack, Value[] locals) {
    static FrameState empty() { return new FrameState(new ArrayDeque<>(), new Value[0]); }
}
```

---

## 9. 模块4：数据流分析与 SSA

### SsaConverter

```java
public class SsaConverter {
    /**
     * 为 LinearIr 中的每个 Variable 分配 SSA 版本号
     *
     * 步骤:
     * 1. 计算支配边界 (Dominance Frontier)
     * 2. 对每个原始变量的每次赋值，递增版本号
     * 3. 在支配边界插入 φ 节点
     * 4. 重命名所有使用
     */
    public LinearIr convert(LinearIr ir);
}
```

### TypeInference

```java
public class TypeInference {
    /**
     * 通过 IR 指令的操作数类型和常量池信息推导每个 Value 的精确类型
     *
     * 来源:
     * - 方法签名 (参数类型、返回类型)
     * - 字段签名
     * - checkcast 指令
     * - instanceof 指令
     * - 调用目标的方法签名中的参数/返回类型
     * - 常量池中的 MethodType
     */
    public LinearIr infer(LinearIr ir, DecompileContext ctx);
}
```

---

## 10. 模块5：控制流结构化

这是整个引擎最复杂的模块。采用**支配树递归分解 + 块折叠**策略。

### 总体流程

```
[LinearIr + CFG]
       │
       ▼ 1. TryCatchAnalyzer  (最先：异常边干扰控制流识别)
       ▼ 2. SwitchAnalyzer   (tableswitch/lookupswitch 天然结构化)
       ▼ 3. LoopAnalyzer     (支配树回边 → Natural Loop → 折叠，由内向外)
       ▼ 4. BranchAnalyzer   (后支配树找 Follow → 提取 Then/Else 子图)
       ▼ 5. IrreducibleHandler (兜底：节点复制 / goto 保留)
       ▼ 6. BlockReducer     (生成最终结构化 BlockStatement)
```

### ControlFlowStructurer — 不可变快照变换策略

**核心规则：每个 fold pass 返回新 ControlFlowGraph 快照，不原地修改。**

```
为什么？
  - 原地修改后 dominator/postDominator 立即失效 → 容易写出"算旧图、改新图"的 bug
  - 返回新快照 → 每次迭代 top 统一重算 dom/postDom → 分析永不过期
  - 调试时可以在任意 fold 前后 dump 图快照，精确定位问题
```

```java
public class ControlFlowStructurer {

    public StructuredMethod structure(LinearIr ir, DecompileContext ctx) {
        ControlFlowGraph graph = ir.controlFlowGraph();

        // 统一计算一次
        DominatorTree dom = DominatorTree.compute(graph);
        PostDominatorTree postDom = PostDominatorTree.compute(graph);

        // 1. Try-Catch 优先
        graph = tryCatchAnalyzer.extract(graph, ir.method());
        dom = DominatorTree.compute(graph);        // 图变了，重算
        postDom = PostDominatorTree.compute(graph);

        // 2. Switch
        graph = switchAnalyzer.extract(graph, dom);
        dom = DominatorTree.compute(graph);
        postDom = PostDominatorTree.compute(graph);

        // 3. 迭代折叠：每次成功折叠后统一重算支配树
        boolean changed = true;
        int maxIterations = graph.blockCount() * 2;
        while (changed && maxIterations-- > 0) {
            changed = false;

            // 循环优先（由内向外）
            List<LoopInfo> loops = loopAnalyzer.analyze(graph, dom);
            if (!loops.isEmpty()) {
                for (LoopInfo loop : sortInnermostFirst(loops)) {
                    graph = foldLoop(graph, loop, postDom);
                    changed = true;
                }
                // 统一重算（一次迭代可能有多个 loop 折叠）
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                continue;
            }

            // If-Else
            List<IfInfo> ifs = branchAnalyzer.analyze(graph, dom, postDom);
            if (!ifs.isEmpty()) {
                for (IfInfo ifInfo : ifs) {
                    graph = foldIf(graph, ifInfo);
                    changed = true;
                }
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                continue;
            }

            // 序列折叠
            ControlFlowGraph prev = graph;
            graph = foldSequences(graph);
            if (graph != prev) {
                dom = DominatorTree.compute(graph);
                postDom = PostDominatorTree.compute(graph);
                changed = true;
            }
        }

        // 4. 不可约图兜底
        if (graph.blockCount() > 1) {
            graph = irreducibleHandler.handle(graph);
        }

        // 5. 生成 AST
        BlockStatement body = blockReducer.reduce(graph);
        return new StructuredMethod(ir.method(), ir, body);
    }
}
```

**每个 fold 方法的签名统一为：**

```java
// 输入旧图 + 分析结果 → 输出新图（不修改输入）
ControlFlowGraph foldLoop(ControlFlowGraph input, LoopInfo loop, PostDominatorTree postDom);
ControlFlowGraph foldIf(ControlFlowGraph input, IfInfo info);
ControlFlowGraph foldSequences(ControlFlowGraph input);
```

### LoopAnalyzer — 支配树回边检测

```
回边定义: 边 N → H，且 H dominates N
  - H = 循环头 (Loop Header)
  - N = 循环尾 (Loop Latch)

自然循环体: {H} ∪ {能到达N且不经过H的所有节点 union}

循环类型判定:
  - header 是唯一出口 → while(cond) { body }
  - latch 是出口       → do { body } while(cond)
  - 多出口             → while + break/continue
  - 无出口             → infinite loop
```

### BranchAnalyzer — 后支配树找 Follow

对于出度为2的节点 H (条件分支头):
- Follow F = immediatePostDominator(H) — 分支汇聚点
- If-Then:  一个后继 == F，另一个 != F
- If-Else:  两个后继都 != F，各自流向 F

### TryCatchAnalyzer

```
输入: CFG 中的 ExceptionRange 列表

1. Catch 识别: catchType != null → 创建 Catch 虚拟节点
2. Finally 识别: catchType == null，检测代码复制
   — 对 try 区域的每条出口边，检查目标块后继是否包含相似代码序列
   — 相似序列 → Finally 虚拟节点
3. Try-With-Resources: catch+finally 组合 + Throwable.addSuppressed 模式
```

---

## 11. 模块6：AST 设计

### 节点层次

```
AstNode (interface)
├── CompilationUnit
├── TypeDeclaration
├── Statement (sealed)
│   ├── BlockStatement
│   ├── IfStatement
│   ├── LoopStatement       (WHILE/DO_WHILE/FOR/FOR_EACH)
│   ├── SwitchStatement
│   ├── TryStatement
│   ├── ReturnStatement
│   ├── ThrowStatement
│   ├── ExpressionStatement
│   ├── BreakStatement
│   ├── ContinueStatement
│   ├── VariableDeclaration
│   ├── AssertStatement
│   ├── SynchronizedStatement
│   └── LabeledStatement
├── Expression (sealed)
│   ├── LiteralExpression
│   ├── VariableExpression
│   ├── BinaryExpression    (+, -, *, /, ==, !=, &&, ||, ...)
│   ├── UnaryExpression     (-, !, ~, ++, --)
│   ├── AssignmentExpression (=, +=, -=, ...)
│   ├── ConditionalExpression (?:)
│   ├── InvocationExpression
│   ├── FieldAccessExpression
│   ├── ArrayAccessExpression
│   ├── CastExpression
│   ├── InstanceOfExpression
│   ├── NewExpression
│   ├── LambdaExpression
│   └── SwitchExpression
└── MemberDeclaration
    ├── FieldDeclaration
    ├── MethodDeclaration
    ├── ConstructorDeclaration
    └── StaticInitializer
```

### 核心接口

```java
public interface AstNode {
    AstKind kind();
    List<AstNode> children();
    <R, C> R accept(AstVisitor<R, C> visitor, C context);
    Optional<SourceRange> sourceRange();
}

public abstract sealed class Statement permits BlockStatement, IfStatement, ... {
    private SourceRange sourceRange;
}

public abstract sealed class Expression permits LiteralExpression, BinaryExpression, ... {
    private JavaType inferredType;
    private int precedence;          // 括号决策关键属性
}
```

### Expression.precedence() — 运算符优先级

```java
public final class Precedence {
    // 常量优先级
    public static final int ASSIGNMENT   = 1;    // = += -=
    public static final int TERNARY      = 2;    // ? :
    public static final int LOGICAL_OR   = 3;    // ||
    public static final int LOGICAL_AND  = 4;    // &&
    public static final int BITWISE_OR   = 5;    // |
    public static final int EQUALITY     = 8;    // == !=
    public static final int RELATIONAL   = 9;    // < > <= >= instanceof
    public static final int SHIFT        = 10;   // << >> >>>
    public static final int ADDITIVE     = 11;   // + -
    public static final int MULTIPLICATIVE = 12; // * / %
    public static final int UNARY        = 13;   // - ! ~ ++ --
    public static final int PRIMARY      = 15;   // 字面量/变量/new/lambda

    /** child 作为 parent 的操作数时是否需要括号 */
    public static boolean needsParentheses(Expression parent, Expression child,
                                            boolean isRightOperand);
}
```

---

## 12. 模块7：AST Rewrite 管线

### 设计：线性规则管线 (CFR 风格)

```java
public interface RewriteRule {
    String name();
    String description();
    CompilationUnit rewrite(CompilationUnit unit, DecompileContext context);
}

public abstract class AbstractRewriteRule implements RewriteRule {
    // Visitor 遍历 AST，对 match() 的子树调用 replace()
    protected abstract boolean match(AstNode node, DecompileContext ctx);
    protected abstract AstNode replace(AstNode node, DecompileContext ctx);
}
```

### 规则列表及优先级

```
高优先级 (结构级重写):
  StringConcatRule      — StringBuilder.append() → 字符串 +
  TryWithResourceRule   — try-catch-finally → try-with-resources
  EnumRewriter          — 识别 enum 类
  RecordRewriter        — 识别 record 类

中优先级 (表达式级重写):
  TernaryRule           — If+赋值 → ? :
  LambdaRule            — invokedynamic → lambda/method ref
  StringSwitchRule      — switch(hash) → switch(string)
  EnumSwitchRule        — switch(ordinal) → switch(enum)
  DiamondOperatorRule   — new Type<>(...)
  ForEachRule           — Iterator while → for-each
  AutoBoxingRule        — 消除显式 Integer.valueOf / intValue
  AssertRule            — if(!cond) throw → assert
  SynchronizedRule      — monitorenter/exit → synchronized

低优先级 (清理):
  UnusedVariableCleanupRule — 消除未使用临时变量
  DeadCodeEliminationRule   — 消除不可达代码
  ConstantFoldRule          — 2+3 → 5
```

### 示例：TernaryRule

```
匹配模式:
  IfStatement(
    condition,
    then: BlockStatement([ ExpressionStatement(Assignment(var, val1)) ]),
    else: BlockStatement([ ExpressionStatement(Assignment(var, val2)) ])
  )
  且 then/else 都向同一个变量赋值

替换:
  ExpressionStatement(
    AssignmentExpression(var, ASSIGN, ConditionalExpression(condition, val1, val2))
  )
```

---

## 13. 模块8：源码输出 (Source Emitter)

### 架构：两遍遍历 + 不修改 AST

```
[CompilationUnit]
       │
       ▼ (第一遍: TypeCollector)
  ImportManager.collectTypeReferences()
       │
       ▼ (第二遍: TypeEmitter → StatementEmitter → ExpressionEmitter)
       │
  IndentWriter — 缩进/换行/空格
  Precedence.needsParentheses() — 括号决策
  LineMappingBuilder — 行号映射
       │
       ▼
  SourceFile(source, lineNumberMapping)
```

### ImportManager

```java
public class ImportManager {
    // 职责:
    // 1. 收集代码中所有类型引用
    // 2. java.lang 自动导入
    // 3. 同包不需要 import
    // 4. 名称冲突时使用全限定名

    public String registerType(String qualifiedName);
    public List<String> finalizeImports();
}
```

### IndentWriter

```java
public class IndentWriter {
    // 自动在行首插入缩进
    public IndentWriter indent();       // +1 缩进层级
    public IndentWriter dedent();       // -1 缩进层级
    public IndentWriter write(String text);
    public IndentWriter token(String keyword);  // 关键字（前后自动空格）
    public IndentWriter newLine();
    public int currentLine();
}
```

---

## 14. 模块9：类型系统

```java
public interface JavaType {
    TypeKind kind();                     // PRIMITIVE / CLASS / ARRAY / TYPE_VARIABLE / WILDCARD
    String displayName();                // 显示名 (如 "java.util.List<String>")
    String descriptor();                 // JVM 描述符 (如 "Ljava/util/List;")
    List<JavaType> typeArguments();      // 泛型参数
    int arrayDimensions();               // 数组维度
}

public enum TypeKind {
    VOID, BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE,
    CLASS, ARRAY, TYPE_VARIABLE, WILDCARD, METHOD_TYPE
}
```

---

## 15. 诊断系统与调试导出

### 诊断系统 — 结构化字段

```java
public enum DiagnosticLevel { INFO, WARNING, ERROR }

/**
 * 结构化诊断信息 — 所有字段固定，不允许靠 message string 传递结构化数据
 */
public record DecompilerDiagnostic(
    // === 元信息（必填） ===
    DiagnosticLevel level,        // 级别
    String phase,                 // 产生阶段: "parser" / "cfg" / "ir" / "structuring" / "ast" / "rewrite" / "emit"

    // === 位置信息（可选但固定字段） ===
    String className,             // 所在类全限定名，null = 未知
    String methodName,            // 所在方法名 + 描述符，null = 未知
    int bytecodeOffset,           // 相关字节码偏移，-1 = 无关

    // === 问题详情 ===
    String message,               // 人类可读描述（简短，单行，不带位置前缀）
    Throwable cause               // 原始异常，null = 无
) {
    // 工厂方法 — 强制调用者明确填入 phase/class/method/offset

    /** 全局诊断 (class 解析阶段) */
    public static DecompilerDiagnostic info(String phase, String className, String msg) {
        return new DecompilerDiagnostic(INFO, phase, className, null, -1, msg, null);
    }

    /** 方法级警告 */
    public static DecompilerDiagnostic warning(String phase, String className,
                                                String methodName, int offset, String msg) {
        return new DecompilerDiagnostic(WARNING, phase, className, methodName, offset, msg, null);
    }

    /** 方法级错误 */
    public static DecompilerDiagnostic error(String phase, String className,
                                              String methodName, int offset, String msg, Throwable cause) {
        return new DecompilerDiagnostic(ERROR, phase, className, methodName, offset, msg, cause);
    }
}
```

### DOT 导出

```java
// CFG → Graphviz DOT 格式（可视化控制流图）
public class DotExporter {
    // 颜色编码:
    //   TRUE_BRANCH → green
    //   FALSE_BRANCH → red
    //   GOTO → dashed
    //   EXCEPTION → orange

    public static String toDot(ControlFlowGraph cfg);
}
```

### AST 树导出

```java
// AST → 缩进树形文本（调试重写效果）
public class AstTreeExporter {
    // 输出格式:
    //   └── COMPILATION_UNIT
    //       ├── TYPE_DECLARATION [Foo]
    //       │   ├── METHOD [bar]
    //       │   │   └── IF
    //       │   │       ├── BINARY [==]
    //       │   │       ├── BLOCK [...]
    //       │   │       └── BLOCK [...]

    public static String toTree(AstNode root);
}
```

---

## 16. 引擎配置项 — Typed Config

```java
/**
 * 类型化配置 — 通过 Builder 构造，不暴露字符串键
 */
public class BdecConfig {

    // === 输出 ===
    private final int indentSize;                // 默认 4
    private final String lineSeparator;          // 默认 "\n"
    private final boolean showLineNumbers;       // 默认 false
    private final boolean showBytecodeOffsets;   // 默认 false

    // === 结构化开关 ===
    private final boolean decodeEnums;           // 默认 true
    private final boolean decodeLambdas;         // 默认 true
    private final boolean decodeTernary;         // 默认 true
    private final boolean decodeStringConcat;    // 默认 true
    private final boolean decodeTryResource;     // 默认 true
    private final boolean decodeForEach;         // 默认 true
    private final boolean collapseImports;        // 默认 true

    // === SSA ===
    private final int ssaThreshold;              // 多少块以上启用 SSA（0=总是, -1=禁用）

    // === 调试 ===
    private final boolean debugDumpCfg;          // 默认 false
    private final boolean debugDumpAst;          // 默认 false

    private BdecConfig(Builder b) { ... }

    public int indentSize() { return indentSize; }
    public boolean decodeEnums() { return decodeEnums; }
    // ... 其余 getter ...

    public static Builder builder() { return new Builder(); }

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
        private int ssaThreshold = 5;           // 默认≥5个块的复杂方法启用 SSA
        private boolean debugDumpCfg = false;
        private boolean debugDumpAst = false;

        public Builder indentSize(int n) { this.indentSize = n; return this; }
        public Builder decodeEnums(boolean v) { this.decodeEnums = v; return this; }
        public Builder ssaThreshold(int n) { this.ssaThreshold = n; return this; }
        // ... 其余 builder setter ...

        public BdecConfig build() { return new BdecConfig(this); }
    }

    /** 默认生产配置 */
    public static BdecConfig defaults() { return builder().build(); }
    /** 调试配置（开启 dump）*/
    public static BdecConfig debug() {
        return builder().debugDumpCfg(true).debugDumpAst(true).build();
    }
}
```

---

## 17. 设计模式总览

### 接口边界原则

**只有这些点是接口，其余全部是具体类：**

| 接口 | 理由 |
|---|---|
| `AttributeParser` | 属性解析器可插拔注册，新版本属性自由扩展 |
| `RewriteRule` | 语法糖规则独立可插拔，用户可自定义 |
| `DiagnosticListener` | 诊断输出可接入不同后端（日志/UI/静默） |
| `Decompiler` | 引擎入口，方便不同上下文（CLI/IDE/批量）切换 |

**以下是具体类（非接口）：**

`ClassFileModel`, `BasicBlock`, `ControlFlowGraph`, `ControlFlowEdge`, `LinearIr`, `IrInstruction`, `Variable`, `Statement`/`Expression` 及其子类, `DominatorTree`, `LoopInfo`, `IfInfo`, `SourceFile`, `JavaType` 等

全部使用 `record` 或 `sealed class`，编译期保证模式匹配完整性。

### 设计模式应用

| 模式 | 应用位置 | 说明 |
|---|---|---|
| **Builder** | `CfgBuilder`, `IrBuilder`, `AstBuilder`, `BdecConfig.Builder` | 复杂对象分步构建 |
| **Strategy** | `DominatorTree` 算法选择 | 固定点 vs L-T 可切换 |
| **Registry** | `AttributeParser` 注册表 | 属性解析器可插拔 |
| **Pipeline** | `BdecEngine` → 各阶段顺序执行 | 分阶段处理 |
| **Visitor** | `AstVisitor<R,C>` → Emitter/Rewriter | AST 遍历 |
| **Chain of Responsibility** | `RewriteRule` 列表 | 语法糖检测管线 |
| **Record (DTO)** | `LoopInfo`, `IfInfo`, `SourceFile`, `FrameState` 等 | 不可变数据传输 |
| **Sealed Class** | `Statement`, `Expression` | 编译期模式匹配检查 |
| **Observer** | `DiagnosticListener` | 诊断信息报告 |
| **Immutable Snapshots** | CFG 结构化变换 | 每次 fold 返回新图，避免分析失效 |

---

## 18. 实现路线图

### Phase 0: 项目骨架 (1-2 天)
- 整理包结构（按设计的包重排现有文件）
- 删除多余的接口定义 — 只保留 `AttributeParser`/`RewriteRule`/`DiagnosticListener`/`Decompiler` 四个接口
- CFG/IR/AST/分析结果全部改为 record 或 sealed 具体类
- `BdecEngine` 骨架（空实现，管线串联）
- `BdecConfig.Builder` typed config
- `DiagnosticListener` + 结构化 `DecompilerDiagnostic`
- 跑通第一个测试：加载一个最简单的 .class（`public class Empty {}`），解析 → 反编译 → 输出

### Phase 1a: Class 解析器 — 核心路径 (2-3 天)
- 核心常量池（Utf8/Integer/Float/Long/Double/Class/String/FieldRef/MethodRef/InterfaceMethodRef/NameAndType）
- `ClassFileReader`（魔数/版本/标志/this class/super class/interfaces）
- 字段解析（签名 + ConstantValue 属性）
- 方法解析（签名 + Code 属性）
- 关键属性：`Code`, `LineNumberTable`, `LocalVariableTable`, `StackMapTable`
- **高频指令先行**：load/store 全系列、const 全系列、基本算术(iadd/isub/...)、return 全系列、invokevirtual/invokespecial/invokestatic、getfield/putfield、分支指令(ifeq/ifne/goto)、dup/swap/pop
- 目标：能解析 JDK 自带类，验证方法签名 + 指令条数正确

### Phase 1b: Class 解析器 — 全量补齐 (2-3 天)
- 剩余常量池（MethodHandle/MethodType/Dynamic/InvokeDynamic/Module/Package）
- 剩余属性（Exceptions/Signature/Deprecated/Annotations/InnerClasses/EnclosingMethod/NestHost/NestMembers/Record/PermittedSubclasses/BootstrapMethods）
- 剩余指令（tableswitch/lookupswitch/invokedynamic/invokeinterface/multianewarray/wide/...）
- `AttributeParser` 注册表 + 未知属性诊断警告
- 测试：解析所有 JDK 自带类，零 crash

### Phase 2: CFG 构建 + 支配树 (3-4 天)
- `CfgBuilder`（Leader 识别 → 基本块划分 → 边建连）
- 虚拟 entry/exit 块 + `ExceptionRange`
- `DominatorTree`（固定点迭代）+ `PostDominatorTree`
- `DotExporter`（可视化调试）
- 测试：验证支配关系正确性

### Phase 3: 栈模拟 + IR 构建 (4-6 天) ⭐
- `FrameState` — 栈+局部变量状态
- `IrBuilder` — 逐块符号执行（所有指令类型）
- 多前驱入口状态合并 + PHI 标记
- 测试：简单表达式/带分支方法验证 IR

### Phase 4: 控制流结构化 (5-8 天) ⭐⭐ 最难
- `TryCatchAnalyzer` → `SwitchAnalyzer` → `LoopAnalyzer` → `BranchAnalyzer`
- `BlockReducer` — 迭代折叠
- `IrreducibleHandler` — 不可约图兜底
- 测试：各类控制流模式验证

### Phase 5: AST Builder + Rewrite (3-4 天)
- `AstBuilder` — 结构化产物 → `CompilationUnit`
- `AstVisitor` / `AstTransformer` 遍历框架
- 所有 Rewrite 规则实现
- 测试：语法糖识别正确性

### Phase 6: Source Emitter (2-3 天)
- `IndentWriter` + `ImportManager` + `Precedence`
- `TypeEmitter` + `StatementEmitter` + `ExpressionEmitter`
- `LineMappingBuilder`
- 测试：编译回环（反编译 → javac → 再反编译）

---

## 19. 测试策略

| 阶段 | 单元测试 | 集成/验证测试 |
|---|---|---|
| P1 Parser | 每种常量池、每条指令解码 | 解析 JDK class 验证字段/方法数 |
| P2 CFG | 支配关系计算、边类型正确性 | DOT 导出 + 人工抽查 |
| P3 IR | 每种指令类型的栈模拟 | IR 指令数/顺序正确 |
| P4 Structuring | Loop/If/Switch 模式识别 | 结构化产物 == 预期控制流节点 |
| P5 AST Rewrite | 每条规则独立测试 | 规则组合不破坏 AST |
| P6 Emit | 括号决策、import 冲突 | **编译回环** (Round-Trip) |

### 测试方式

1. **单元测试** — 核心数据结构（CFG/支配树/栈模拟/结构化）
2. **黄金文件测试** — 已知 .class → 预期 .java 输出（特定语法糖场景）
3. **编译回环测试** — 反编译输出 → javac 重新编译 → 再反编译 → 两次结果一致

---

## 20. 参考项目分析摘要

### CFR
- **IR**：4 阶段 Opcode Graph (Op01→Op02→Op03→Op04)
- **结构化**：~70 个 Rewriter 线性管道 + Recovery 机制
- **特点**：`BlockIdentifier` 分组 + 渐进式 Unstructured→Structured 转换
- **亮点**：Sink/Factory 输出抽象、DFS 图算法、多级恢复策略

### Procyon
- **IR**：双层 AST（`com.strobel.decompiler.ast` IR + `languages.java.ast` Java AST）
- **结构化**：`AstOptimizer` 59 步顺序优化 + 支配树检测 Loop/If
- **特点**：Role-based 子节点组织、Pattern Matching 引擎、Language 插件架构
- **亮点**：强大的类型推导（`TypeAnalysis`）、`MetadataSystem` 类型缓存

### Vineflower
- **IR**：单层 Statement 图（节点 + 类型化 StatEdge）
- **结构化**：支配树递归分解（`DomHelper.processStatement()`）+ 边类型精化
- **特点**：9 个 Pass Hook 插件钩子、SSA+SSAU 双形式、MatchEngine 模式匹配
- **亮点**：`FastFixedSet` 位集优化的支配计算、`FinallyProcessor` 代码复制检测

### JD-Core
- 轻量级，结构较简单，与另外三个相比架构复杂度较低

---

*本设计文档基于 CFR、Procyon、Vineflower、JD-Core 四个反编译引擎的源码分析，以及 JDK 25 javac 编译逻辑的参考，综合 bdec 项目"零依赖、通用引擎、正确性优先"的定位而制定。*
