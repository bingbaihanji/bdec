# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在此仓库中工作时提供指导。

## 项目概述

BDEC（Bingbaihanji Decompiler Engine Core）是一个面向 Java 25 的 Java 字节码反编译器。它接受 `.class` 文件或 JAR 包作为输入，生成可读的 Java 源代码。引擎每次处理一个类，通过分层管线逐步将 IR 从原始字节码提升为结构化 AST，最终输出源代码。

## 构建与测试

```bash
# 构建
mvn compile

# 运行全部测试
mvn test

# 运行单个测试类
mvn test -Dtest=BdecEngineTest

# 运行单个测试方法
mvn test -Dtest=BdecEngineTest#testEngineNameAndVersion

# 打包可执行 JAR
mvn package
# 生成 target/bdec.jar，主类: com.bingbaihanji.bdec.BdecCli
```

项目使用 **Java 25**、**Maven** 和 **JUnit 4.13.2**。测试需要 JDK（而非 JRE），因为往返测试框架使用 `javax.tools.JavaCompiler` 编译 Java 源码。

## 架构：反编译管线

`BdecEngine.decompile()` 编排一个固定顺序的管线。每个阶段都是独立的、可替换的组件：

```
字节码 (.class bytes)
  → ClassFileReader         （解析 class 文件头、常量池、方法、字段）
  → CfgBuilder              （将字节码指令划分为基本块，连接 ControlFlowEdge）
  → IrBuilder               （栈模拟 → 寄存器式 LinearIr，保留操作码元数据）
  → SemanticReconstructor    （恢复高层语义：字符串拼接、for-each 模式等）
  → [SsaBuilder → TypeInference → CopyPropagation → DeadCodeElimination]  （可选，由 ssaThreshold 控制）
  → ControlFlowStructurer   （迭代折叠循环、合并顺序块、标注 if/switch/try-catch）
  → AstBuilder              （从 ClassFileModel + StructuredMethod 构建 CompilationUnit AST）
  → AstRewriter             （约 18 条重写规则链，见下文）
  → SourceEmitter           （将 AST 美化输出为 Java 源码，含缩进、import、行号映射）
```

管线定义在 `BdecEngine.java:147-238`。每个阶段都是构造函数中初始化的字段。

## 关键类型及其职责

| 类型 | 职责 |
|------|------|
| `BdecEngine` | 管线编排器；实现 `Decompiler` 接口 |
| `BdecConfig` | 通过 `BdecConfig.builder()...build()` 构建的不可变配置；包含每个重写器的功能开关 |
| `DecompileContext` | 单次反编译的上下文，携带配置、字节码加载回调、BootstrapMethod 表、ClassFileModel |
| `ClassFileModel` / `MethodModel` / `FieldModel` | 字节码级别的解析表示 |
| `ControlFlowGraph` | 带有类型化 `ControlFlowEdge`（`EdgeKind`）的 CFG；不可变的前驱/后继视图；惰性支配树 |
| `BasicBlock` | 单入口单出口的指令序列；指令和异常范围在构造时设定，之后不可变 |
| `LinearIr` | 寄存器式 IR（非基于栈）；携带 `List<IrInstruction>`、关联的 `MethodModel` + CFG、变量表 |
| `IrInstruction` | 单个 IR 操作，包含 `IrOpcode`、操作数和原始字节码元数据 |
| `StructuredMethod` | 结构化的输出：方法模型 + IR + 归约后的 AST `BlockStatement` 体 |
| `CompilationUnit` | 顶层 AST：包声明、import、`List<TypeDeclaration>`、内部类名称 |
| `AstNode` | 所有 AST 节点的基类；下设 `Expression` 和 `Statement` 子层级 |

## CFG 中的边类型

`EdgeKind` 枚举（`cfg/EdgeKind.java`）：`ENTRY`、`FALL_THROUGH`、`TRUE_BRANCH`、`FALSE_BRANCH`、`GOTO`、`SWITCH_CASE`、`SWITCH_DEFAULT`、`EXCEPTION`、`RETURN`、`THROW`。这些边类型对结构化至关重要——例如，`FALL_THROUGH` 边驱动顺序块合并，`TRUE_BRANCH`/`FALSE_BRANCH` 驱动 if-else 识别。

## 控制流结构化算法

`ControlFlowStructurer.structure()` 位于 `structuring/ControlFlowStructurer.java`：

1. 计算支配树和后支配树
2. 预分析 switch、try-catch、if-else 和循环模式（生成标注：`SwitchInfo`、`TryCatchInfo`、`IfInfo`、`LoopInfo`）
3. **迭代折叠**循环（最内层优先），合并 fallthrough 顺序块。每次折叠创建一个新的 `ControlFlowGraph` 快照。if/else 块不会被折叠——`BlockReducer` 直接从标注构建 `IfStatement` 节点
4. 在最终折叠后的图上重新分析 if-else 和 try-catch（预分析中的块引用可能已过时）
5. `BlockReducer` 使用标注将最终 CFG 转换为 `BlockStatement` AST
6. `FinallyRecognizer` 合并共享同一处理器的相邻 try-finally 块

关键文件：`LoopAnalyzer`、`BranchAnalyzer`、`SwitchAnalyzer`、`TryCatchAnalyzer`、`BlockReducer`、`FinallyRecognizer`、`IrreducibleHandler`。

## AST 重写规则链

在 AST 构建后按顺序应用（`BdecEngine.java:82-94`）：

1. `RecordRewriter` — record 类（规范构造器、访问器方法）
2. `SealedClassRewriter` — sealed 类/接口 permits 子句
3. `LambdaRewriter` — `invokedynamic` → lambda 表达式
4. `MethodRefRewriter` — 方法引用（`::`）
5. `StringConcatRewriter` — `StringBuilder`/`Indy` 拼接 → `+` 链
6. `TextBlockRewriter` — 内联 `\n` 字符串 → 文本块
7. `ForEachRewriter` — 基于迭代器的循环 → 增强 for-each
8. `TryResourceRewriter` — try-finally close → try-with-resources
9. `SwitchExprRewriter` — switch 语句 → switch 表达式（箭头语法）
10. `PatternMatchRewriter` — `instanceof` + 强制转型 → 模式匹配
11. `RecordPatternRewriter` — record 解构模式
12. `TernaryRewriter` — if-else 赋值 → 三元 `?:`
13. `BoxingRewriter` — `Integer.valueOf()` / `intValue()` → 自动装箱
14. `StringSwitchRewriter` — 基于哈希的 switch → 字符串 switch
15. `EnumSwitchRewriter` — 基于序数的 switch → 枚举 switch
16. `EnumRewriter` — `$VALUES` 数组 + 静态初始化 → 枚举声明
17. `InnerClassRewriter` — 合成访问器 → 直接字段访问
18. `SourceCleanup` — 最终清理（未使用变量、作用域调整）

每个重写器实现 `AstRewriter.AstRewriteRule` 接口。它们接收 `DecompileContext` 以访问 bootstrap method、class 文件模型和字节码加载。

## 测试方式：往返编译-反编译

项目使用往返测试。编写正常的 Java 源码，用 `javac` 编译，用 BDEC 反编译生成的 class，然后断言输出包含预期的模式。

**测试框架**：`DecompileTestHarness`（`src/test/java/com/bingbaihanji/bdec/DecompileTestHarness.java`）

```java
DecompileTestHarness h = new DecompileTestHarness();
String output = h.decompileSource("class Foo { int x = 1; }", "Foo");
DecompileTestHarness.assertContains(output, "int x = 1;");
```

`src/test/java/com/bytecode/test/` 中的类（如 `TestClass1.java`、`EnumDemo.java`、`RecordDemo.java`）由 Maven 预编译，通过 `BytecodeTestRoundTripTest` 测试。`src/test/resources/decompile-samples/m2-controlflow/` 中的示例源码在测试时编译。

**添加新功能时**：添加一个往返测试——编译使用该功能的 Java 源码，反编译，断言输出包含预期的高层结构且不包含底层痕迹（如字符串拼接不应出现 `StringBuilder`，内部类访问不应出现合成 `access$` 方法）。

## 添加新的 AST 重写器

1. 在 `src/main/java/com/bingbaihanji/bdec/ast/rewrite/` 中创建规则类
2. 实现 `AstRewriter.AstRewriteRule` 接口（或扩展现有基类）
3. 在 `BdecConfig` 中添加配置开关（字段 + builder 方法 + getter，设置合理的默认值）
4. 在 `BdecEngine` 的 `AstRewriter` 构造函数列表中注册规则——顺序很重要
5. 添加往返测试

## 调试输出

使用 `BdecConfig.debug()` 获取 CFG DOT 导出和 AST 树转储。`DotExporter` 工具（`util/DotExporter.java`）可以将 `ControlFlowGraph` 渲染为 Graphviz DOT 格式。单独的配置开关：`debugDumpCfg(true)` 和 `debugDumpAst(true)`。

## 重要约束

- **CFG 边由 `ControlFlowGraph` 管理，而非 `BasicBlock`**。使用 `cfg.outgoingOf(b)` / `cfg.successorsOf(b)`，永远不要直接修改 BasicBlock 自身的列表。
- **ControlFlowGraph 在构造后实质上不可变**。结构化折叠通过 `buildFoldedGraph` 创建新图，永远不原地修改。
- **支配树是惰性计算的**（使用不动点迭代法），并缓存在 CFG 实例上。
- **AST 重写器不得原地修改树**——它们返回新节点。`AstRewriter` 类负责 visitor 分发和树重建。
- **内部类**在 `BdecEngine.decompileInnerClasses()` 中递归反编译。匿名/局部类（名称以 `$` + 数字结尾）跳过单独反编译，改为内联处理。
- **`DecompileContext` 携带的 `classFile` 可为 null**——重写器在访问字节码级别数据时必须进行 null 检查。
